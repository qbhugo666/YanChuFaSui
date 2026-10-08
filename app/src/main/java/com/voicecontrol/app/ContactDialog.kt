package com.voicecontrol.app

import android.app.Activity
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.Toast

/** 关于页和设置页共用的反馈入口。只复制用户主动公开的账号，不收集或发送反馈内容。 */
object ContactDialog {
    private const val WECHAT_ID = "HXH2003qb"
    private const val QQ_ID = "252206091"

    fun show(activity: Activity) {
        val dialog = Dialog(activity)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_contact)
        dialog.setCancelable(true)
        dialog.setCanceledOnTouchOutside(true)

        fun copy(label: String, account: String) {
            val clipboard = activity.getSystemService(Activity.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText(label, account))
            Toast.makeText(activity, "${label}已复制", Toast.LENGTH_SHORT).show()
        }

        dialog.findViewById<View>(R.id.btn_copy_wechat).setOnClickListener { copy("微信号", WECHAT_ID) }
        dialog.findViewById<View>(R.id.btn_copy_qq).setOnClickListener { copy("QQ 号", QQ_ID) }
        dialog.findViewById<View>(R.id.btn_contact_done).setOnClickListener { dialog.dismiss() }

        dialog.show()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        val margin = (32 * activity.resources.displayMetrics.density).toInt()
        val width = (420 * activity.resources.displayMetrics.density).toInt()
        dialog.window?.setLayout(
            minOf(width, activity.resources.displayMetrics.widthPixels - margin),
            WindowManager.LayoutParams.WRAP_CONTENT
        )
    }
}
