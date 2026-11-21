package fr.michawaro.sfrremote;

import android.util.Base64;
import android.os.Handler;
import android.os.Looper;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Locale;
import java.util.Set;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Small RFC 6455 client with no external dependencies. Only the local STB8
 * on TCP port 7682 is contacted. This is an unofficial / experimental protocol.
 */
public final class SfrWebSocket {
    public interface Listener {
        void onState(String state, String message);
        void onMessage(String message);
    }
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final SecureRandom random = new SecureRandom();
    private final Object writeLock = new Object();
    private volatile boolean running = false;
    private volatile boolean connected = false;
    private volatile long generation = 0;
    private Socket socket;
    private InputStream input;
    private OutputStream output;
    private final String deviceId="default-stb8";
    private final AtomicLong requestSequence = new AtomicLong(System.currentTimeMillis());
    private static final Set<String> ALLOWED = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
       "volUp","volDown","channelUp","channelDown","home","back","power",
       "fastForward","fastBackward","playPause","stop","record","mute",
       "up","left","right","down","ok","0","1","2","3","4","5","6","7","8","9")));

    public SfrWebSocket(Listener listener) { this.listener=listener; }

    public static boolean validKey(String s){ return s!=null && ALLOWED.contains(s); }
    public static boolean isLocalIPv4(String value) {
        if(value == null) return false;
        String[] chunks=value.trim().split("\\.",-1);
        if(chunks.length!=4) return false;
        int[] n=new int[4];
        try {
            for(int i=0;i<4;i++){
                if(!chunks[i].matches("[0-9]{1,3}")) return false;
                n[i]=Integer.parseInt(chunks[i]);if(n[i]>255) return false;
            }
        } catch(NumberFormatException e) {return false;}
        return n[0]==10 || (n[0]==192 && n[1]==168) || (n[0]==172 && n[1]>=16 && n[1]<=31);
    }

    public void connect(String host) {
        close();
        long myGeneration=++generation;
        running=true;
        Thread t = new Thread(() -> connectLoop(host.trim(),myGeneration),"SFR8-Network");
        t.setDaemon(true);
        t.start();
    }

    private void connectLoop(String host,long token) {
        int backoff=1000;
        while(running && generation==token) {
            emitState("connecting","Connexion à "+host+":7682…");
            Socket local = null;
            try {
                local = new Socket();
                local.connect(new InetSocketAddress(host,7682),2300);
                local.setSoTimeout(30000);
                InputStream in=local.getInputStream(); OutputStream out=local.getOutputStream();
                synchronized(writeLock) {
                    if(generation!=token){local.close();return;}
                    socket=local;input=in;output=out;
                }
                handshake(host,in,out);
                connected=true;
                emitState("connected","Connexion établie");
                backoff=1000;
                sendStatus();
                receiveLoop(in);
            } catch(Exception e) {
                if(running && token==generation) emitState("disconnected", friendlyError(e));
            } finally {
                if(token==generation) connected=false;
                closeSocket(local);
            }
            if(!running || token!=generation) break;
            try {Thread.sleep(backoff);} catch(InterruptedException e){Thread.currentThread().interrupt();break;}
            backoff=Math.min(15000,backoff*2);
        }
    }

    private String friendlyError(Exception e) {
        String s=e.getMessage();
        return "Décodeur inaccessible. Vérifie son adresse IP, son alimentation et le Wi-Fi." + (s==null?"":" ("+s+")");
    }
    private void emitState(String state,String message) { main.post(()->listener.onState(state,message)); }
    private void emitMessage(String message) { main.post(()->listener.onMessage(message)); }

    private void handshake(String host, InputStream in, OutputStream out) throws Exception {
        byte[] nonce=new byte[16];random.nextBytes(nonce);
        String key=Base64.encodeToString(nonce,Base64.NO_WRAP);
        String http="GET /ws HTTP/1.1\r\nHost: "+host+":7682\r\n"+
                "Upgrade: websocket\r\nConnection: Upgrade\r\n"+
                "Sec-WebSocket-Key: "+key+"\r\nSec-WebSocket-Version: 13\r\n\r\n";
        out.write(http.getBytes(StandardCharsets.US_ASCII));out.flush();
        ByteArrayOutputStream raw=new ByteArrayOutputStream();
        int last4=0;
        while(raw.size()<8192) {
            int ch=in.read();if(ch<0) throw new EOFException("Réponse WebSocket absente");
            raw.write(ch);last4=((last4<<8)|ch);
            if(last4==0x0d0a0d0a) break;
        }
        if(raw.size()>=8192) throw new IOException("Réponse HTTP trop longue");
        String response=raw.toString("ISO-8859-1");
        if(!response.startsWith("HTTP/1.1 101") && !response.startsWith("HTTP/1.0 101"))
            throw new IOException("Le port 7682 ne répond pas en WebSocket (101 attendu)");
        String expected=Base64.encodeToString(MessageDigest.getInstance("SHA-1").digest(
            (key+"258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.US_ASCII)),Base64.NO_WRAP);
        if(!response.toLowerCase(Locale.ROOT).contains("sec-websocket-accept: "+expected.toLowerCase(Locale.ROOT)))
            throw new IOException("Signature WebSocket incorrecte");
    }

    private void receiveLoop(InputStream in) throws Exception {
        while(running && connected) {
            int b0;
            try {b0=in.read();} catch(java.net.SocketTimeoutException e) {writeFrame(0x9,new byte[0]);continue;}
            if(b0<0) throw new EOFException("Connexion fermée");
            int b1=in.read();if(b1<0) throw new EOFException();
            int opcode=b0&0x0f;
            long size=b1&0x7f;
            if(size==126){size=((long)readByte(in)<<8)|readByte(in);}
            else if(size==127){size=0;for(int i=0;i<8;i++)size=(size<<8)|readByte(in);}
            if(size<0 || size>1048576) throw new IOException("Trame WebSocket trop grande");
            byte[] mask=null;
            if((b1&0x80)!=0){mask=new byte[4];readFully(in,mask);}
            byte[] data=new byte[(int)size];readFully(in,data);
            if(mask!=null){for(int i=0;i<data.length;i++)data[i]^=mask[i%4];}
            if(opcode==0x8) throw new EOFException("Décodeur déconnecté");
            if(opcode==0x9) writeFrame(0xA,data);
            if(opcode==0x1) emitMessage(new String(data,StandardCharsets.UTF_8));
        }
    }
    private static int readByte(InputStream in)throws IOException {int b=in.read();if(b<0)throw new EOFException();return b;}
    private static void readFully(InputStream in,byte[] data)throws IOException {
        int pos=0;while(pos<data.length){int n=in.read(data,pos,data.length-pos);if(n<0)throw new EOFException();pos+=n;}
    }

    private void writeFrame(int opcode, byte[] data) throws IOException {
        synchronized(writeLock){
            if(output==null) throw new IOException("Pas de connexion");
            if(data.length>65535) throw new IOException("Message trop grand");
            byte[] mask=new byte[4];random.nextBytes(mask);
            output.write(0x80|opcode);
            if(data.length<126)output.write(0x80|data.length);
            else {output.write(0x80|126);output.write((data.length>>>8)&255);output.write(data.length&255);}
            output.write(mask);
            byte[] encoded=data.clone();for(int i=0;i<encoded.length;i++)encoded[i]^=mask[i%4];
            output.write(encoded);output.flush();
        }
    }
    private void sendMessage(String action,String key) {
        if(!connected)return;
        try {
            JSONObject obj=new JSONObject();obj.put("action",action);obj.put("deviceId",deviceId);
            obj.put("requestId",requestSequence.updateAndGet(last -> Math.max(last + 1, System.currentTimeMillis())));
            if(key!=null){JSONObject p=new JSONObject();p.put("key",key);obj.put("params",p);}
            writeFrame(0x1,obj.toString().getBytes(StandardCharsets.UTF_8));
        } catch(Exception e){emitState("disconnected","Erreur de communication : "+e.getMessage());}
    }
    public void sendKey(String key){ if(validKey(key))sendMessage("buttonEvent",key); }
    public void sendExperimentalKey(String key){
        if(key!=null && key.matches("[a-z0-9]|space"))sendMessage("buttonEvent",key);
    }
    public void sendStatus(){sendMessage("getStatus",null);}
    private void closeSocket(){
        synchronized(writeLock){
            if(socket!=null){try{socket.close();}catch(Exception ignored){}}
            socket=null;input=null;output=null;
        }
    }
    private void closeSocket(Socket toClose) {
        synchronized(writeLock) {
            if(toClose != null) {
                try {toClose.close();} catch(Exception ignored) {}
                if (socket == toClose) { socket=null;input=null;output=null; }
            }
        }
    }
    public void close(){running=false;connected=false;generation++;closeSocket();}
}
