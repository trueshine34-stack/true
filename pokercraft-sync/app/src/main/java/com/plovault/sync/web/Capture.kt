package com.plovault.sync.web

import android.util.Base64
import android.webkit.JavascriptInterface
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList

/** Запрос, перехваченный внутри страницы PokerCraft. */
data class CapturedRequest(
    val url: String,
    val method: String,
    val headers: Map<String, String>,
    val body: String,
    val status: Int,
    val contentType: String,
    val at: Long = System.currentTimeMillis()
) {
    /** Похоже ли это на запрос выгрузки истории рук. */
    val exportScore: Int
        get() {
            var s = 0
            val u = url.lowercase()
            val ct = contentType.lowercase()
            if (u.contains("download")) s += 3
            if (u.contains("export")) s += 3
            if (u.contains("handhistory") || u.contains("hand-history") || u.contains("hand_history")) s += 5
            if (u.contains("history")) s += 2
            if (u.contains("rush")) s += 1
            if (u.contains("gamehistory") || u.contains("game-history")) s += 2
            if (ct.contains("zip") || ct.contains("octet-stream")) s += 5
            if (ct.contains("text/plain")) s += 1
            if (status in 200..299) s += 1
            return s
        }
}

/** Файл, пойманный на странице (blob или обычная загрузка). */
data class CapturedFile(
    val name: String,
    val bytes: ByteArray,
    val at: Long = System.currentTimeMillis()
)

/** Общее хранилище перехваченного — живёт, пока открыт экран PokerCraft. */
object CaptureStore {
    val requests = CopyOnWriteArrayList<CapturedRequest>()
    val files = CopyOnWriteArrayList<CapturedFile>()

    @Volatile
    var listener: (() -> Unit)? = null

    @Volatile
    var onFile: ((CapturedFile) -> Unit)? = null

    fun clear() {
        requests.clear()
        files.clear()
        listener?.invoke()
    }

    fun addRequest(r: CapturedRequest) {
        requests.add(0, r)
        while (requests.size > 300) requests.removeAt(requests.size - 1)
        listener?.invoke()
    }

    fun addFile(f: CapturedFile) {
        files.add(0, f)
        while (files.size > 10) files.removeAt(files.size - 1)
        listener?.invoke()
        onFile?.invoke(f)
    }

    fun bestExportRequest(): CapturedRequest? =
        requests.filter { it.exportScore >= 5 }.maxByOrNull { it.exportScore }
}

/** Мост JS → Kotlin. */
class JsBridge {

    @JavascriptInterface
    fun onEvent(json: String) {
        try {
            val o = JSONObject(json)
            when (o.optString("type")) {
                "req" -> {
                    val headers = HashMap<String, String>()
                    o.optJSONObject("headers")?.let { h ->
                        h.keys().forEach { k -> headers[k] = h.optString(k) }
                    }
                    CaptureStore.addRequest(
                        CapturedRequest(
                            url = o.optString("url"),
                            method = o.optString("method", "GET").uppercase(),
                            headers = headers,
                            body = o.optString("body", ""),
                            status = o.optInt("status", 0),
                            contentType = o.optString("ctype", "")
                        )
                    )
                }
                "blob" -> {
                    val data = o.optString("data")
                    val comma = data.indexOf(',')
                    if (comma > 0) {
                        val raw = Base64.decode(data.substring(comma + 1), Base64.DEFAULT)
                        CaptureStore.addFile(CapturedFile(o.optString("name", "export.bin"), raw))
                    }
                }
            }
        } catch (e: Exception) {
            // перехват не должен ломать страницу
        }
    }
}

/** JS, который внедряется в страницу PokerCraft. */
object CaptureScript {
    const val JS = """
(function(){
  if (window.__ploVaultHooked) return; window.__ploVaultHooked = true;
  function post(o){ try { PloVault.onEvent(JSON.stringify(o)); } catch(e){} }
  function abs(u){ try { return new URL(u, location.href).href; } catch(e){ return String(u); } }

  var origFetch = window.fetch;
  if (origFetch) {
    window.fetch = function(input, init){
      var url = (typeof input === 'string') ? input : (input && input.url) || '';
      var method = (init && init.method) || (input && input.method) || 'GET';
      var body = '';
      try { if (init && init.body && typeof init.body === 'string') body = init.body; } catch(e){}
      var headers = {};
      try {
        if (init && init.headers) {
          if (typeof init.headers.forEach === 'function') init.headers.forEach(function(v,k){ headers[k]=v; });
          else Object.keys(init.headers).forEach(function(k){ headers[k]=init.headers[k]; });
        }
      } catch(e){}
      var p = origFetch.apply(this, arguments);
      try {
        p.then(function(r){
          var ct = '';
          try { ct = r.headers.get('content-type') || ''; } catch(e){}
          post({type:'req', url:abs(url), method:method, body:body, headers:headers, status:r.status, ctype:ct});
        }).catch(function(){});
      } catch(e){}
      return p;
    };
  }

  var oOpen = XMLHttpRequest.prototype.open;
  var oSend = XMLHttpRequest.prototype.send;
  var oSet  = XMLHttpRequest.prototype.setRequestHeader;
  XMLHttpRequest.prototype.open = function(m,u){ this.__m=m; this.__u=u; this.__h={}; return oOpen.apply(this, arguments); };
  XMLHttpRequest.prototype.setRequestHeader = function(k,v){ try{ this.__h[k]=v; }catch(e){} return oSet.apply(this, arguments); };
  XMLHttpRequest.prototype.send = function(b){
    var self = this;
    try {
      this.addEventListener('load', function(){
        var ct = '';
        try { ct = self.getResponseHeader('content-type') || ''; } catch(e){}
        post({type:'req', url:abs(self.__u), method:(self.__m||'GET'), body:(typeof b === 'string' ? b : ''), headers:(self.__h||{}), status:self.status, ctype:ct});
      });
    } catch(e){}
    return oSend.apply(this, arguments);
  };

  var oCreate = URL.createObjectURL;
  if (oCreate) {
    URL.createObjectURL = function(blob){
      var u = oCreate.apply(this, arguments);
      try {
        if (blob && blob.size && blob.size > 0 && blob.size < 60*1024*1024) {
          var fr = new FileReader();
          fr.onload = function(){ post({type:'blob', name:(blob.name || 'pokercraft-export.bin'), size:blob.size, data:fr.result}); };
          fr.readAsDataURL(blob);
        }
      } catch(e){}
      return u;
    };
  }
})();
"""
}
