package com.example.aifittingroom

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread
import kotlin.math.max
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { FittingRoom() }
    }
}

private const val API = "https://aihorde.net/api/v2"
private const val ANON_KEY = "0000000000"

private fun connection(url: String, apiKey: String): HttpURLConnection =
    (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 30_000
        readTimeout = 60_000
        setRequestProperty("apikey", apiKey.ifBlank { ANON_KEY })
        setRequestProperty("Client-Agent", "AI-Fitting-Room:0.6:android")
    }

private fun postJson(url: String, body: String, apiKey: String): String {
    val c = connection(url, apiKey)
    c.requestMethod = "POST"
    c.doOutput = true
    c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
    c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
    val code = c.responseCode
    val stream = if (code in 200..299) c.inputStream else c.errorStream
    val response = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
    if (code !in 200..299) {
        val message = runCatching { JSONObject(response).optString("message") }.getOrNull()
        throw IllegalStateException(message?.takeIf { it.isNotBlank() } ?: "HTTP $code: $response")
    }
    return response
}

private fun getJson(url: String, apiKey: String): String {
    val c = connection(url, apiKey)
    c.requestMethod = "GET"
    val code = c.responseCode
    val stream = if (code in 200..299) c.inputStream else c.errorStream
    val response = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
    if (code !in 200..299) throw IllegalStateException("HTTP $code: $response")
    return response
}

private data class PreparedImage(val base64: String, val width: Int, val height: Int)

// AI Horde expects a Base64 source image. Re-encode to WebP and preserve the original aspect ratio.
private fun prepareImage(bytes: ByteArray): PreparedImage {
    val source = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        ?: throw IllegalArgumentException("Не удалось прочитать фотографию")
    val scale = minOf(1f, 1024f / max(source.width, source.height))
    val rawW = (source.width * scale).roundToInt().coerceAtLeast(64)
    val rawH = (source.height * scale).roundToInt().coerceAtLeast(64)
    val w = ((rawW / 64) * 64).coerceAtLeast(64)
    val h = ((rawH / 64) * 64).coerceAtLeast(64)
    val resized = Bitmap.createScaledBitmap(source, w, h, true)
    val out = ByteArrayOutputStream()
    resized.compress(Bitmap.CompressFormat.WEBP, 88, out)
    if (resized !== source) resized.recycle()
    source.recycle()
    return PreparedImage(Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP), w, h)
}

private fun makeRequest(
    image: PreparedImage, outfit: String, prompt: String, denoise: Float, apiKey: String
): String {
    val params = JSONObject().apply {
        put("sampler_name", "k_euler_a")
        put("steps", 25)
        put("cfg_scale", 7.5)
        put("denoising_strength", denoise.toDouble())
        put("width", image.width)
        put("height", image.height)
    }
    val description = "photorealistic fashion photograph of the same adult person, wearing $outfit, " +
        "tasteful fully clothed fashion styling, realistic garment construction and fabric, " +
        "preserve the person's identity, pose, body proportions and background. " +
        prompt.trim()
    val body = JSONObject().apply {
        put("prompt", description)
        put("params", params)
        put("source_image", image.base64)
        put("source_processing", "img2img")
        put("nsfw", false)
        put("censor_nsfw", true)
        put("r2", true)
        put("shared", false)
    }
    return postJson("$API/generate/async", body.toString(), apiKey)
}

private fun pollResult(id: String, apiKey: String, onDone: (String?, String?) -> Unit) {
    thread {
        try {
            repeat(120) {
                Thread.sleep(3000)
                val status = JSONObject(getJson("$API/generate/status/$id", apiKey))
                if (status.optBoolean("faulted")) {
                    onDone(null, "AI Horde не смог выполнить задание. Попробуй ещё раз.")
                    return@thread
                }
                if (status.optBoolean("done")) {
                    val generations = status.optJSONArray("generations")
                    val imageUrl = if (generations != null && generations.length() > 0)
                        generations.getJSONObject(0).optString("img", "") else ""
                    onDone(imageUrl.takeIf { it.startsWith("https://") }, null)
                    return@thread
                }
            }
            onDone(null, "Задание всё ещё в очереди. Попробуй проверить позже.")
        } catch (e: Exception) {
            onDone(null, e.message ?: "Ошибка сети")
        }
    }
}

private fun saveImageToGallery(url: String, save: (String?) -> Unit) {
    thread {
        try {
            val conn = URL(url).openConnection()
            val mime = conn.contentType?.substringBefore(';')?.takeIf { it.startsWith("image/") } ?: "image/png"
            val extension = when (mime) { "image/webp" -> "webp"; "image/jpeg" -> "jpg"; else -> "png" }
            val bytes = conn.getInputStream().use { it.readBytes() }
            val resolver = appContextForSave ?: throw IllegalStateException("Нет доступа к хранилищу")
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "AI_Fitting_Room_${System.currentTimeMillis()}.$extension")
                put(MediaStore.Images.Media.MIME_TYPE, mime)
                if (Build.VERSION.SDK_INT >= 29) put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/AI Fitting Room")
            }
            val uri = resolver.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("Не удалось создать файл в галерее")
            resolver.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                ?: throw IllegalStateException("Не удалось сохранить изображение")
            save(null)
        } catch (e: Exception) { save(e.message ?: "Ошибка сохранения") }
    }
}

private var appContextForSave: android.content.Context? = null

@Composable
fun FittingRoom() {
    val context = LocalContext.current
    appContextForSave = context.applicationContext
    var imageUri by remember { mutableStateOf<Uri?>(null) }
    var resultUrl by remember { mutableStateOf<String?>(null) }
    var outfit by remember { mutableStateOf("Глубокий вырез") }
    var strength by remember { mutableFloatStateOf(0.55f) }
    var prompt by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("Выбери фотографию взрослого человека") }
    var count by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        imageUri = uri
        resultUrl = null
        if (uri != null) status = "Фото выбрано"
    }
    val outfits = listOf("Глубокий вырез", "Открытый купальник", "Элегантное бельё", "Вечернее платье", "Клубный образ", "Деловой стиль")

    MaterialTheme {
        Column(Modifier.fillMaxSize().padding(18.dp).verticalScroll(rememberScrollState())) {
            Text("AI Fitting Room", style = MaterialTheme.typography.headlineMedium)
            Text("Виртуальная примерка одежды", modifier = Modifier.padding(bottom = 16.dp))
            Button(onClick = { picker.launch("image/*") }, modifier = Modifier.fillMaxWidth()) {
                Text(if (imageUri == null) "Выбрать фото" else "Выбрать другое фото")
            }
            imageUri?.let { AsyncImage(model = it, contentDescription = "Исходное фото", modifier = Modifier.fillMaxWidth().heightIn(max = 340.dp).padding(top = 10.dp)) }
            Spacer(Modifier.height(14.dp))
            Text("Выбери образ", style = MaterialTheme.typography.titleMedium)
            outfits.forEach { item ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(item, Modifier.padding(vertical = 5.dp))
                    RadioButton(selected = outfit == item, onClick = { outfit = item })
                }
            }
            Text("Сила изменения: ${(strength * 100).toInt()}%")
            Slider(value = strength, onValueChange = { strength = it }, valueRange = 0.25f..0.85f)
            OutlinedTextField(value = prompt, onValueChange = { prompt = it }, label = { Text("Дополнительные пожелания") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = apiKey, onValueChange = { apiKey = it }, label = { Text("Личный AI Horde API-ключ (необязательно)") }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(12.dp))
            Button(
                enabled = imageUri != null && !busy && count < 10,
                onClick = {
                    val uri = imageUri ?: return@Button
                    busy = true
                    status = "Подготавливаю фотографию…"
                    thread {
                        try {
                            val original = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                                ?: throw IllegalStateException("Не удалось открыть фото")
                            val prepared = prepareImage(original)
                            val response = JSONObject(makeRequest(prepared, outfit, prompt, strength, apiKey))
                            val id = response.optString("id")
                            if (id.isBlank()) throw IllegalStateException(response.optString("message", "AI Horde не вернул ID задания"))
                            (context as? ComponentActivity)?.runOnUiThread {
                                count++
                                status = "Задание отправлено. Ожидаю свободный сервер…"
                            }
                            pollResult(id, apiKey) { url, error ->
                                (context as? ComponentActivity)?.runOnUiThread {
                                    busy = false
                                    if (url != null) { resultUrl = url; status = "Готово! Результат ниже." }
                                    else status = error ?: "Генерация завершилась без изображения"
                                }
                            }
                        } catch (e: Exception) {
                            (context as? ComponentActivity)?.runOnUiThread {
                                busy = false
                                status = "Ошибка: ${e.message}"
                            }
                        }
                    }
                }, modifier = Modifier.fillMaxWidth()
            ) { Text(if (busy) "Генерация…" else "Создать образ ($count/10)") }
            resultUrl?.let { url ->
                Spacer(Modifier.height(14.dp))
                AsyncImage(model = url, contentDescription = "Результат примерки", modifier = Modifier.fillMaxWidth().heightIn(min = 240.dp))
                OutlinedButton(onClick = {
                    status = "Сохраняю в галерею…"
                    saveImageToGallery(url) { error ->
                        (context as? ComponentActivity)?.runOnUiThread {
                            status = if (error == null) "Сохранено в Pictures/AI Fitting Room" else "Ошибка сохранения: $error"
                        }
                    }
                }, modifier = Modifier.fillMaxWidth()) { Text("Сохранить в галерею") }
            }
            Spacer(Modifier.height(12.dp))
            Text(status)
            Text("Используй только фотографии взрослых людей с их согласия. Приложение предназначено для примерки одежды, а не для создания наготы или снятия одежды с реальных людей.", style = MaterialTheme.typography.bodySmall)
            Text("Анонимный режим имеет низкий приоритет в очереди AI Horde. Личный ключ можно получить на сайте AI Horde.", style = MaterialTheme.typography.bodySmall)
        }
    }
}
