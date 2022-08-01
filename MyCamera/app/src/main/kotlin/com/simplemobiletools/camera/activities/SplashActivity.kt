package com.simplemobiletools.camera.activities

import android.annotation.SuppressLint
import android.content.Intent
import com.simplemobiletools.commons.activities.BaseSplashActivity

@SuppressLint("CustomSplashScreen")
class SplashActivity : BaseSplashActivity() {
    override fun initActivity() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
