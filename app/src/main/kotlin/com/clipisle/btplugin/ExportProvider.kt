package com.clipisle.btplugin
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import com.clipisle.app.bt.BtRuntime
import java.io.File
import java.io.FileNotFoundException
class ExportProvider : ContentProvider() {
 override fun onCreate() = true
 private fun resolve(uri: Uri): Pair<File, String> {
  if (uri.pathSegments.size != 2) throw FileNotFoundException()
  val task = uri.pathSegments[0]; val index = uri.pathSegments[1].toIntOrNull() ?: throw FileNotFoundException()
  if (!PluginProtocol.validExport(task, index)) throw FileNotFoundException()
  val result = BtRuntime.get(context!!).getTaskFiles(task).get()
  val entry = (result["files"] as? List<*>)?.filterIsInstance<Map<*, *>>()?.firstOrNull { (it["fileIndex"] as? Number)?.toInt() == index && it["available"] == true && it["hidden"] != true } ?: throw FileNotFoundException()
  val file = File(entry["outputPath"] as? String ?: throw FileNotFoundException())
  val root = File(context!!.filesDir, "downloads").canonicalFile
  if (!file.canonicalPath.startsWith(root.path + File.separator) || !file.isFile) throw FileNotFoundException()
  return file to (entry["mime"] as? String ?: "application/octet-stream")
 }
 override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor { if (mode != "r") throw FileNotFoundException(); return ParcelFileDescriptor.open(resolve(uri).first, ParcelFileDescriptor.MODE_READ_ONLY) }
 override fun getType(uri: Uri) = resolve(uri).second
 override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
  val f = resolve(uri).first; val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
  return MatrixCursor(columns).apply { addRow(columns.map { when (it) { OpenableColumns.DISPLAY_NAME -> f.name; OpenableColumns.SIZE -> f.length(); else -> null } }) }
 }
 override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
 override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = throw UnsupportedOperationException()
 override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = throw UnsupportedOperationException()
}
