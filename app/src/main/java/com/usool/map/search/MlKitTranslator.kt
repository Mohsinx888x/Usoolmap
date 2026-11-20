package com.usool.map.search

import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { cont.resume(it) }
    addOnFailureListener { cont.resumeWithException(it) }
    addOnCanceledListener { cont.cancel() }
}

/**
 * On-device translation with Google ML Kit. Each language is a model of about 30 MB that is
 * downloaded once from Google and then works with no connection. The download itself needs
 * access to Google's servers, so it must be done before travelling to places where they are
 * blocked (for example mainland China).
 */
class MlKitTranslator : TextTranslator {
    private val manager by lazy { RemoteModelManager.getInstance() }
    private val clients = HashMap<String, Translator>()

    private fun code(language: String): String? = TranslateLanguage.fromLanguageTag(language.substringBefore('-').lowercase())

    fun supports(language: String): Boolean = code(language) != null

    private suspend fun installed(language: String): Boolean {
        val c = code(language) ?: return false
        if (c == TranslateLanguage.ENGLISH) return true
        return manager.isModelDownloaded(TranslateRemoteModel.Builder(c).build()).await()
    }

    override suspend fun isReady(from: String, to: String): Boolean =
        runCatching { installed(from) && installed(to) }.getOrDefault(false)

    /** Downloads the models for [languages]. Throws when a language is unsupported or the download fails. */
    suspend fun download(languages: List<String>) {
        for (language in languages.distinct()) {
            val c = code(language) ?: throw IllegalArgumentException("Translation is not available for \"$language\".")
            if (c == TranslateLanguage.ENGLISH) continue
            manager.download(TranslateRemoteModel.Builder(c).build(), DownloadConditions.Builder().build()).await()
        }
    }

    override suspend fun translate(texts: List<String>, from: String, to: String): List<String?> {
        val source = code(from) ?: return texts.map { null }
        val target = code(to) ?: return texts.map { null }
        val client = synchronized(clients) {
            clients.getOrPut("$source>$target") {
                Translation.getClient(TranslatorOptions.Builder().setSourceLanguage(source).setTargetLanguage(target).build())
            }
        }
        return texts.map { text -> runCatching { client.translate(text).await() }.getOrNull() }
    }
}
