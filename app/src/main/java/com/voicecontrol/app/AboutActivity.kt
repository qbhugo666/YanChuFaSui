package com.voicecontrol.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast

/**
 * 关于页（v0.32.0）：真正的软件介绍——是什么 / 能做什么 / 隐私承诺 / 支持。
 * 此前主页底部「关于」跳的是设置页、设置里的「关于」只是三行弹窗（用户点名要详细介绍）。
 */
class AboutActivity : ThemedActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_about)

        findViewById<android.view.View>(R.id.btn_back).setOnClickListener { finish() }

        findViewById<TextView>(R.id.tv_version).text =
            "v${packageManager.getPackageInfo(packageName, 0).versionName}"

        findViewById<android.view.View>(R.id.row_coffee).setOnClickListener {
            runCatching {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(MainActivity.AFDIAN_URL)))
            }.onFailure {
                Toast.makeText(this, "打不开浏览器，请稍后再试", Toast.LENGTH_SHORT).show()
            }
        }
        findViewById<android.view.View>(R.id.row_contact).setOnClickListener {
            ContactDialog.show(this)
        }
    }
}
