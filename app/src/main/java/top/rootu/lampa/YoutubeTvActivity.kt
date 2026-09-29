package top.rootu.lampa

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity

/**
 * Full-screen in-app YouTube TV (leanback) player, mirroring the lampa-desktop YouTube button.
 *
 * The desktop opens a BrowserWindow at https://www.youtube.com/tv with a spoofed PlayStation 4
 * "Leanback Shell" User-Agent: YouTube then serves the 10-foot TV UI and streams 1080p/4K without
 * the strict Smart-TV DRM (Widevine L1) checks. We replicate that in a WebView here. A small close
 * button is injected top-right for D-pad exit (same as desktop); Back navigates YouTube history and
 * exits when there is none.
 */
class YoutubeTvActivity : AppCompatActivity() {

    private lateinit var web: WebView

    companion object {
        // Community "PS4 Leanback Shell" UA — the same string used by the lampa-desktop YouTube window.
        private const val PS4_LEANBACK_UA =
            "Mozilla/5.0 (PS4; Leanback Shell) Gecko/20100101 Firefox/65.0 LeanbackShell/01.00.01.75 Sony PS4/ (PS4, , no, CH)"

        // Injected after load: a focusable close button (top-right) + key handling, so a D-pad user
        // can leave. Calls back into the native side via AndroidYT.close().
        private const val CLOSE_BUTTON_JS = """
(function(){
  if(document.getElementById('lampa-yt-close-btn')) return;
  var ICON='<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24"><path fill="none" stroke="currentColor" stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M18 6L6 18M6 6l12 12"/></svg>';
  var b=document.createElement('div');
  b.id='lampa-yt-close-btn'; b.innerHTML=ICON; b.tabIndex=0;
  b.style.cssText='position:fixed;top:18px;right:18px;z-index:99999;width:52px;height:52px;color:#fff;background:rgba(0,0,0,0.55);border-radius:50%;padding:12px;box-sizing:border-box;display:flex;align-items:center;justify-content:center;transition:all .2s;outline:none;';
  var st=document.createElement('style');
  st.textContent='#lampa-yt-close-btn:focus{background:rgba(229,9,20,1)!important;transform:scale(1.12);box-shadow:0 0 14px rgba(229,9,20,.8);border:2px solid #fff;}';
  document.head.appendChild(st);
  b.onclick=function(){ try{AndroidYT.close();}catch(e){} };
  document.body.appendChild(b);
  document.addEventListener('keydown',function(e){
    if(e.key==='Enter' && document.activeElement===b){ try{AndroidYT.close();}catch(e2){} }
  });
})();
"""
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        web = WebView(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.BLACK)
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                mediaPlaybackRequiresUserGesture = false
                userAgentString = PS4_LEANBACK_UA
                loadWithOverviewMode = true
                useWideViewPort = true
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
            }
            // Keep the YouTube leanback SPA inside this WebView.
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView, request: WebResourceRequest
                ): Boolean = false

                override fun onPageFinished(view: WebView, url: String) {
                    super.onPageFinished(view, url)
                    view.evaluateJavascript(CLOSE_BUTTON_JS) { }
                }
            }
            webChromeClient = WebChromeClient()
            addJavascriptInterface(YtBridge(), "AndroidYT")
        }
        setContentView(web)
        immersive()
        web.loadUrl("https://www.youtube.com/tv")

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (web.canGoBack()) web.goBack() else finish()
            }
        })
    }

    inner class YtBridge {
        @android.webkit.JavascriptInterface
        fun close() {
            runOnUiThread { finish() }
        }
    }

    // Route hardware media/dpad keys into the WebView (YouTube leanback handles them itself).
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        return super.onKeyDown(keyCode, event)
    }

    private fun immersive() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                )
        }
    }

    override fun onDestroy() {
        try {
            web.loadUrl("about:blank")
            (web.parent as? ViewGroup)?.removeView(web)
            web.destroy()
        } catch (_: Exception) {
        }
        super.onDestroy()
    }
}
