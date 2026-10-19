package io.liriliri.eruda.mcp

import android.content.Context
import android.graphics.PixelFormat
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
 * Popup de pairing que aparece FORA do app (sobre qualquer tela), via
 * overlay do sistema (TYPE_APPLICATION_OVERLAY). Requer a permissão
 * especial "Exibir sobre outros apps".
 *
 * É a superfície intermediária do McpPairing: só aparece quando o app NÃO
 * está na frente e a permissão existe. Approve/Deny resolvem na hora;
 * o X dispensa o popup e deixa uma notificação com as mesmas ações.
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

    fun show(ctx: Context, id: String, code2: Int, clientName: String) {
        main.post {
            try {
                if (!canShow(ctx)) return@post
                hideNow()
                val v = LayoutInflater.from(ctx).inflate(R.layout.overlay_pairing, null)
                v.findViewById<TextView>(R.id.ovCode).text = code2.toString()
                v.findViewById<TextView>(R.id.ovClient).text = clientName
                v.findViewById<Button>(R.id.btnOvApprove).setOnClickListener {
                    McpPairing.approveClicked(id)
                }
                v.findViewById<Button>(R.id.btnOvDeny).setOnClickListener {
                    McpPairing.denyClicked(id)
                }
                v.findViewById<TextView>(R.id.btnOvClose).setOnClickListener {
                    McpPairing.dismissClicked(id)
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
        main.post { if (id == null || id == currentId) hideNow() }
    }

    private fun hideNow() {
        view?.let { v ->
            try {
                val wm = v.context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                wm.removeView(v)
            } catch (e: Exception) {
                // já removida
            }
        }
        view = null
        currentId = null
    }
}
