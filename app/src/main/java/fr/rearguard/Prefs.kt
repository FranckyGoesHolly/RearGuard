package fr.rearguard

import android.content.Context

object Prefs {
    private fun sp(c: Context) = c.getSharedPreferences("rearguard", Context.MODE_PRIVATE)
    fun cameraId(c: Context): String? = sp(c).getString("camera", null)
    fun setCameraId(c: Context, id: String) = sp(c).edit().putString("camera", id).apply()
    fun sensitivity(c: Context): Int = sp(c).getInt("sensitivity", 50)
    fun setSensitivity(c: Context, v: Int) = sp(c).edit().putInt("sensitivity", v).apply()
    fun centerZone(c: Context): Boolean = sp(c).getBoolean("centerZone", true)
    fun setCenterZone(c: Context, v: Boolean) = sp(c).edit().putBoolean("centerZone", v).apply()
    fun autoStart(c: Context): Boolean = sp(c).getBoolean("autoStart", false)
    fun setAutoStart(c: Context, v: Boolean) = sp(c).edit().putBoolean("autoStart", v).apply()
}
