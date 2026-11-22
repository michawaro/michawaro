package com.altremote.tvremote

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Direct Android TV remote, local only, via TLS Remote v2. */
class ConnectTvController(context: Context, private val callback: Listener) {
  interface Listener {
    fun onState(state: String, message: String)
    fun onFound(name: String, host: String)
    fun onMessage(message: String)
  }
  private val ctx = context.applicationContext
  private val prefs = ctx.getSharedPreferences("connecttv_paired", Context.MODE_PRIVATE)
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val main = Handler(Looper.getMainLooper())
  private val pairing = PairingManager()
  private var discovery: TvDiscovery? = null
  @Volatile private var host = ""
  @Volatile private var session: RemoteSession? = null
  @Volatile private var status = "disconnected"
  private fun state(s: String, msg: String) {
    status = s
    main.post { callback.onState(s, msg) }
  }
  private fun message(msg: String) { main.post { callback.onMessage(msg) } }
  fun discover() {
    main.post {
      try {
        discovery?.stop()
        discovery = TvDiscovery(ctx, object : TvDiscovery.Listener {
          override fun onFound(tv: TvDiscovery.FoundTv) {
            main.post { callback.onFound(tv.serviceName, tv.host) }
          }
          override fun onLost(serviceName: String) {}
          override fun onStateChanged(running: Boolean, error: String?) {
            if (error != null) message("Recherche : $error")
            else if (running) message("Recherche sur le Wi-Fi…")
          }
        })
        discovery?.start()
      } catch (e: Exception) { state("error", "Recherche impossible : ${e.message}") }
    }
  }
  fun connect(address: String) {
    val ip = address.trim()
    if (!localIp(ip)) { state("error", "Adresse IP locale invalide"); return }
    host = ip
    session?.close()
    session = null
    val fingerprint = prefs.getString("fp_$ip", null)
    if (fingerprint == null) startPair(ip) else openRemote(ip, fingerprint)
  }
  fun newPair() {
    if (!localIp(host)) { state("error", "Entre l'adresse IP du décodeur"); return }
    session?.close()
    prefs.edit().remove("fp_$host").apply()
    startPair(host)
  }
  private fun startPair(ip: String) {
    state("pairing", "Demande d'association envoyée…")
    scope.launch {
      try {
        pairing.start(ip, "Télécommande Express")
        state("pair_code", "Saisis le code affiché sur la télévision")
      } catch (e: Exception) { state("error", "Association impossible : ${e.message}") }
    }
  }
  fun pairCode(code: String) {
    val value = code.trim().uppercase()
    if (!value.matches(Regex("[0-9A-F]{6}"))) {
      state("pair_code", "Le code comporte 6 caractères : 0-9, A-F")
      return
    }
    scope.launch {
      try {
        val p = pairing.finish(value)
        prefs.edit().putString("fp_$host", p.serverCertFingerprint).apply()
        openRemote(host, p.serverCertFingerprint)
      } catch (e: PairingManager.PairingException) {
        state(if (e.code == "BAD_CODE") "pair_code" else "error",
          if (e.code == "BAD_CODE") "Code incorrect, réessaie" else "Association refusée : ${e.message}")
      } catch (e: Exception) { state("error", "Erreur d'association : ${e.message}") }
    }
  }
  private fun openRemote(ip: String, fingerprint: String?) {
    state("connecting", "Connexion sécurisée à $ip:6466…")
    scope.launch {
      var s: RemoteSession? = null
      try {
        s = RemoteSession(ip, fingerprint, object : RemoteSession.Listener {
          override fun onReady(session: RemoteSession) { state("connected", "Connect TV connectée") }
          override fun onClosed(session: RemoteSession, error: RemoteSession.RemoteException?) {
            if (this@ConnectTvController.session === session) {
              this@ConnectTvController.session = null
              state("disconnected", error?.message ?: "Déconnectée")
            }
          }
          override fun onPowerState(isOn: Boolean) {}
          override fun onVolume(level: Int, max: Int, muted: Boolean) {}
          override fun onCurrentApp(packageName: String) {}
          override fun onDeviceInfo(vendor: String, model: String, appVersion: String) {
            message("Décodeur : $model")
          }
          override fun onImeShow(currentText: String) { message("Clavier TV prêt") }
        })
        session = s
        s.connect(scope)
      } catch (e: Exception) {
        if (session === s) session = null
        state("error", "Connexion impossible : ${e.message}")
      }
    }
  }
  fun sendKey(name: String) {
    val code = KEY_MAP[name] ?: return
    val s = session?.takeIf { it.isReady }
    if (s == null) { message("Connecte d'abord la Connect TV"); return }
    scope.launch {
      try { s.sendKey(code, RemoteSession.Direction.SHORT) }
      catch (e: Exception) { message("Commande refusée : ${e.message}") }
    }
  }
  fun sendText(text: String) {
    val t = text.take(300)
    if (t.isEmpty()) return
    val s = session?.takeIf { it.isReady }
    if (s == null) { message("Connecte d'abord la Connect TV"); return }
    scope.launch {
      try { s.sendText(t) }
      catch (e: Exception) { message("Saisie non disponible : ${e.message}") }
    }
  }
  fun reportState() { state(status, if (status == "connected") "Connectée" else "Non connectée") }
  fun close() {
    main.post { discovery?.stop(); discovery = null }
    pairing.cancel()
    session?.close()
    session = null
    scope.cancel()
  }
  companion object {
    private fun localIp(ip: String): Boolean {
      val p = ip.split(".")
      if (p.size != 4) return false
      val n = p.map { it.toIntOrNull() ?: return false }
      if (n.any { it !in 0..255 }) return false
      return n[0] == 10 || (n[0] == 192 && n[1] == 168) ||
        (n[0] == 172 && n[1] in 16..31)
    }
    private val KEY_MAP = mapOf(
      "home" to KeyEvent.KEYCODE_HOME, "back" to KeyEvent.KEYCODE_BACK,
      "power" to KeyEvent.KEYCODE_POWER, "up" to KeyEvent.KEYCODE_DPAD_UP,
      "down" to KeyEvent.KEYCODE_DPAD_DOWN, "left" to KeyEvent.KEYCODE_DPAD_LEFT,
      "right" to KeyEvent.KEYCODE_DPAD_RIGHT, "ok" to KeyEvent.KEYCODE_DPAD_CENTER,
      "volUp" to KeyEvent.KEYCODE_VOLUME_UP, "volDown" to KeyEvent.KEYCODE_VOLUME_DOWN,
      "mute" to KeyEvent.KEYCODE_VOLUME_MUTE, "channelUp" to KeyEvent.KEYCODE_CHANNEL_UP,
      "channelDown" to KeyEvent.KEYCODE_CHANNEL_DOWN,
      "playPause" to KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
      "fastForward" to KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
      "fastBackward" to KeyEvent.KEYCODE_MEDIA_REWIND,
      "stop" to KeyEvent.KEYCODE_MEDIA_STOP, "record" to KeyEvent.KEYCODE_MEDIA_RECORD,
      "0" to KeyEvent.KEYCODE_0, "1" to KeyEvent.KEYCODE_1,
      "2" to KeyEvent.KEYCODE_2, "3" to KeyEvent.KEYCODE_3,
      "4" to KeyEvent.KEYCODE_4, "5" to KeyEvent.KEYCODE_5,
      "6" to KeyEvent.KEYCODE_6, "7" to KeyEvent.KEYCODE_7,
      "8" to KeyEvent.KEYCODE_8, "9" to KeyEvent.KEYCODE_9
    )
  }
}