package io.liriliri.eruda.mcp

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import io.liriliri.eruda.R

/**
 * Popup de pairing que aparece FORA do app (sobre qualquer tela),
 * via overlay do sistema (TYPE_APPLICATION_OVERLAY).
 *
 * Requer a permissão especial "Exibir sobre outros apps"
 * (Settings.ACTION_MANAGE_OVERLAY_PERMISSION).
 *
 * Limite honesto: o botão Review abre a página de aprovação no
 * navegador — o Approve final acontece lá, porque só o navegador
 * consegue entregar o código ao redirect do cliente. O Deny é
 * imediato (server-side, sem navegador). O X só dispensa o popup.
 */
object McpOverlay {

    private val main = Handler(Looper.getMainLooper())
    private var view: View? = null
    private var currentId: String? = null

    fun canShow(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        return try {
            Settings.canDrawOverlays(context)
        } catch (e: Exception) {
            false
        }
    }

    fun show(ctx: Context, id: String, code2: Int, clientName: String, port: Int) {
        main.post {
            try {
                if (!canShow(ctx)) return@post
                hideNow(ctx)
                val v = LayoutInflater.from(ctx).inflate(R.layout.overlay_pairing, null)
                v.findViewById<TextView>(R.id.ovCode).text = code2.toString()
                v.findViewById<TextView>(R.id.ovClient).text = clientName
                val pageUrl = "http://127.0.0.1:$port/oauth/decision?id=$id"
                v.findViewById<Button>(R.id.btnOvReview).setOnClickListener {
                    try {
                        ctx.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(pageUrl)).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                        )
                    } catch (e: Exception) {
                        McpServerManager.log("overlay review failed: ${e.message}")
                    }
                }
                v.findViewById<Button>(R.id.btnOvDeny).setOnClickListener {
                    McpOAuth.decideDirect(id, false)
                    hideNow(ctx)
                }
                v.findViewById<TextView>(R.id.btnOvClose).setOnClickListener {
                    hideNow(ctx)
                }
                val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE
                }
                val params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    type,
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                }
                val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                wm.addView(v, params)
                view = v
                currentId = id
                McpServerManager.log("Overlay pairing shown (code $code2)")
            } catch (e: Exception) {
                McpServerManager.log("overlay show failed: ${e.message}")
            }
        }
    }

    fun hide(id: String? = null) {
        main.post { hideOnMain(id) }
    }

    private fun hideOnMain(id: String?) {
        if (id != null && id != currentId) return
        view?.let { v ->
            try {
                // WindowManager precisa de contexto com token válido; usa o
                // contexto da própria view quando possível.
                val wm = v.context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                wm.removeView(v)
            } catch (e: Exception) {
                // já removida
            }
        }
        view = null
        currentId = null
    }

    private fun hideNow(ctx: Context) {
        try {
            view?.let {
                val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                wm.removeView(it)
            }
        } catch (e: Exception) {
            // ignore
        }
        view = null
        currentId = null
    }
}
