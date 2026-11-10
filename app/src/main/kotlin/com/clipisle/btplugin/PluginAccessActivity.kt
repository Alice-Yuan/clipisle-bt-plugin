package com.clipisle.btplugin

import android.app.Activity
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Build
import java.security.MessageDigest

internal object HostTrust {
 private const val HOST = "com.clipisle.app"
 fun fingerprint(context: android.content.Context): String? = try {
  val certs = if (Build.VERSION.SDK_INT >= 28) {
   val pkg = context.packageManager.getPackageInfo(HOST, PackageManager.GET_SIGNING_CERTIFICATES)
   val signing = pkg.signingInfo ?: return null
   signing.apkContentsSigners
  } else {
   @Suppress("DEPRECATION")
   context.packageManager.getPackageInfo(HOST, PackageManager.GET_SIGNATURES).signatures
  } ?: return null
  if (certs.size != 1) return null
  MessageDigest.getInstance("SHA-256").digest(certs[0].toByteArray()).joinToString("") { "%02x".format(it) }
 } catch (_: Exception) { null }
 fun isAuthorized(context: android.content.Context): Boolean {
  val current = fingerprint(context) ?: return false
  return context.getSharedPreferences("plugin_trust", 0).getString("host_sha256", null) == current
 }
 fun uidIsHost(context: android.content.Context, uid: Int) = context.packageManager.getPackagesForUid(uid)?.contains(HOST) == true
}

class PluginAccessActivity : Activity() {
 override fun onCreate(state: Bundle?) {
  super.onCreate(state)
  if (callingPackage != "com.clipisle.app") { finish(); return }
  if (HostTrust.isAuthorized(this)) { setResult(RESULT_OK); finish(); return }
  val fingerprint = HostTrust.fingerprint(this) ?: run { finish(); return }
  AlertDialog.Builder(this).setTitle("授权 ClipIsle 访问 BT 插件")
   .setMessage("允许 com.clipisle.app 访问下载任务和文件？\n\n应用签名 SHA-256：\n$fingerprint")
   .setPositiveButton("授权") { _, _ ->
    if (HostTrust.fingerprint(this) == fingerprint && getSharedPreferences("plugin_trust", 0).edit().putString("host_sha256", fingerprint).commit()) setResult(RESULT_OK)
    else setResult(RESULT_CANCELED)
    finish()
   }
   .setNegativeButton("取消") { _, _ -> setResult(RESULT_CANCELED); finish() }
   .setOnCancelListener { finish() }.show()
 }
}
