package io.liriliri.eruda

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog

/** Popup de confirmação com visual custom (ícone destrutivo + botões CLEAR/CANCEL). */
fun showConfirmDialog(
    context: Context,
    titleRes: Int,
    messageRes: Int,
    iconRes: Int = R.drawable.ic_trash,
    onConfirm: () -> Unit
) {
    val view = LayoutInflater.from(context).inflate(R.layout.dialog_confirm, null)
    view.findViewById<ImageView>(R.id.confirmIcon).setImageResource(iconRes)
    view.findViewById<TextView>(R.id.confirmTitle).setText(titleRes)
    view.findViewById<TextView>(R.id.confirmMessage).setText(messageRes)

    val dialog = AlertDialog.Builder(context, R.style.AppDialog).setView(view).create()

    view.findViewById<View>(R.id.btnConfirmCancel).setOnClickListener { dialog.dismiss() }
    view.findViewById<View>(R.id.btnConfirmOk).setOnClickListener {
        onConfirm()
        dialog.dismiss()
    }

    dialog.show()
}
