package dev.dsh.recorder

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** 单个字的估算时间（相对本句 wav 的毫秒）。wrong=true 表示电脑识别认为读错。 */
data class CharTime(
    val ch: String,
    val startMs: Long,
    val endMs: Long,
    val wrong: Boolean = false,
)

data class Sentence(
    val id: String,
    val text: String,
    val wav: String? = null,
    val recordedAt: Long = 0L,
    val durationMs: Long = 0L,
    val sampleRate: Int = 0,
    val deviceName: String = "",
    val chars: List<CharTime> = emptyList(),
    val rate: Float = 0f,
) {
    val recorded: Boolean get() = !wav.isNullOrBlank()
}

data class Project(
    val id: String,
    val name: String,
    val createdAt: Long,
    val sentences: MutableList<Sentence> = mutableListOf(),
) {
    val recordedCount: Int get() = sentences.count { it.recorded }
    val totalMs: Long get() = sentences.sumOf { it.durationMs }
}

/** 项目 / 句子 / 录音文件 的本地存储（JSON + App 专属目录）。 */
object ProjectStore {

    private const val FILE = "projects.json"
    private val lock = Any()

    // ---------------------------------------------------------------- 读写

    fun load(context: Context): MutableList<Project> = synchronized(lock) {
        val file = File(context.filesDir, FILE)
        if (!file.exists()) return mutableListOf()
        val result = mutableListOf<Project>()
        runCatching {
            val root = JSONObject(file.readText())
            val arr = root.optJSONArray("projects") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val p = arr.getJSONObject(i)
                val sentences = mutableListOf<Sentence>()
                val sArr = p.optJSONArray("sentences") ?: JSONArray()
                for (j in 0 until sArr.length()) {
                    val s = sArr.getJSONObject(j)
                    val chars = mutableListOf<CharTime>()
                    val charsArr = s.optJSONArray("chars") ?: JSONArray()
                    for (k in 0 until charsArr.length()) {
                        val item = charsArr.optJSONArray(k) ?: continue
                        chars.add(
                            CharTime(
                                ch = item.optString(0, ""),
                                startMs = item.optLong(1),
                                endMs = item.optLong(2),
                                wrong = if (item.length() > 3) item.optBoolean(3) else false,
                            ),
                        )
                    }
                    sentences.add(
                        Sentence(
                            id = s.optString("id"),
                            text = s.optString("text"),
                            wav = s.optString("wav").ifBlank { null },
                            recordedAt = s.optLong("recordedAt"),
                            durationMs = s.optLong("durationMs"),
                            sampleRate = s.optInt("sampleRate"),
                            deviceName = s.optString("deviceName"),
                            chars = chars,
                            rate = s.optDouble("rate", 0.0).toFloat(),
                        ),
                    )
                }
                result.add(
                    Project(
                        id = p.optString("id"),
                        name = p.optString("name"),
                        createdAt = p.optLong("createdAt"),
                        sentences = sentences,
                    ),
                )
            }
        }
        result
    }

    fun save(context: Context, projects: List<Project>) = synchronized(lock) {
        val root = JSONObject()
        val arr = JSONArray()
        projects.forEach { p ->
            val sArr = JSONArray()
            p.sentences.forEach { s ->
                val charsArr = JSONArray()
                s.chars.forEach { c ->
                    charsArr.put(JSONArray().put(c.ch).put(c.startMs).put(c.endMs).put(c.wrong))
                }
                sArr.put(
                    JSONObject().apply {
                        put("id", s.id)
                        put("text", s.text)
                        put("wav", s.wav ?: "")
                        put("recordedAt", s.recordedAt)
                        put("durationMs", s.durationMs)
                        put("sampleRate", s.sampleRate)
                        put("deviceName", s.deviceName)
                        put("chars", charsArr)
                        put("rate", s.rate.toDouble())
                    },
                )
            }
            arr.put(
                JSONObject().apply {
                    put("id", p.id)
                    put("name", p.name)
                    put("createdAt", p.createdAt)
                    put("sentences", sArr)
                },
            )
        }
        root.put("projects", arr)
        val file = File(context.filesDir, FILE)
        val tmp = File(context.filesDir, "$FILE.tmp")
        tmp.writeText(root.toString())
        if (file.exists()) file.delete()
        tmp.renameTo(file)
    }

    // ---------------------------------------------------------------- 项目

    fun projectDir(context: Context, projectId: String): File =
        File(context.getExternalFilesDir(null), "projects/$projectId").apply { mkdirs() }

    fun create(context: Context, projects: MutableList<Project>, name: String): Project {
        val project = Project(
            id = System.currentTimeMillis().toString(36) + (100..999).random(),
            name = name.trim().ifBlank { "未命名项目" },
            createdAt = System.currentTimeMillis(),
        )
        projects.add(project)
        save(context, projects)
        projectDir(context, project.id)
        return project
    }

    fun deleteProject(context: Context, projects: MutableList<Project>, id: String) {
        projects.removeAll { it.id == id }
        save(context, projects)
        projectDir(context, id).deleteRecursively()
    }

    fun rename(context: Context, projects: MutableList<Project>, id: String, name: String) {
        val project = projects.firstOrNull { it.id == id } ?: return
        val index = projects.indexOf(project)
        projects[index] = project.copy(name = name.trim().ifBlank { project.name })
        save(context, projects)
    }

    // ---------------------------------------------------------------- 句子

    /** 从文本行批量添加句子，返回新增数量。 */
    fun addSentences(context: Context, projects: MutableList<Project>, projectId: String, texts: List<String>): Int {
        val project = projects.firstOrNull { it.id == projectId } ?: return 0
        val cleaned = texts.map { it.trim() }.filter { it.isNotBlank() }
        if (cleaned.isEmpty()) return 0
        val index = projects.indexOf(project)
        val list = project.sentences
        val start = System.currentTimeMillis()
        cleaned.forEachIndexed { i, text ->
            list.add(Sentence(id = "s" + start + "_" + i, text = text))
        }
        projects[index] = project.copy(sentences = list)
        save(context, projects)
        return cleaned.size
    }

    fun addSentence(context: Context, projects: MutableList<Project>, projectId: String, text: String) {
        addSentences(context, projects, projectId, listOf(text))
    }

    fun nextUnrecorded(project: Project, from: Int = 0): Int {
        for (i in from.coerceAtLeast(0) until project.sentences.size) {
            if (!project.sentences[i].recorded) return i
        }
        return (project.sentences.size - 1).coerceAtLeast(0)
    }

    fun wavName(index: Int, text: String): String {
        val snippet = text.replace(Regex("""[\\/:*?"<>|\r\n\t]"""), "_").trim().take(12)
        return "%04d_%s.wav".format(index + 1, snippet.ifBlank { "line" })
    }

    fun markRecorded(
        context: Context,
        projects: MutableList<Project>,
        projectId: String,
        sentenceId: String,
        file: File,
        durationMs: Long,
        sampleRate: Int,
        deviceName: String,
        chars: List<CharTime> = emptyList(),
        rate: Float = 0f,
    ) {
        val pi = projects.indexOfFirst { it.id == projectId }
        if (pi < 0) return
        val project = projects[pi]
        val si = project.sentences.indexOfFirst { it.id == sentenceId }
        if (si < 0) return
        val old = project.sentences[si]
        // 重录时删掉旧文件
        old.wav?.let { rel -> File(projectDir(context, projectId), rel).delete() }
        project.sentences[si] = old.copy(
            wav = file.name,
            recordedAt = System.currentTimeMillis(),
            durationMs = durationMs,
            sampleRate = sampleRate,
            deviceName = deviceName,
            chars = chars,
            rate = rate,
        )
        save(context, projects)
    }

    fun clearRecording(context: Context, projects: MutableList<Project>, projectId: String, sentenceId: String) {
        val pi = projects.indexOfFirst { it.id == projectId }
        if (pi < 0) return
        val project = projects[pi]
        val si = project.sentences.indexOfFirst { it.id == sentenceId }
        if (si < 0) return
        val old = project.sentences[si]
        old.wav?.let { File(projectDir(context, projectId), it).delete() }
        project.sentences[si] = old.copy(wav = null, recordedAt = 0, durationMs = 0)
        save(context, projects)
    }

    fun updateSentenceText(
        context: Context,
        projects: MutableList<Project>,
        projectId: String,
        sentenceId: String,
        text: String,
    ) {
        val pi = projects.indexOfFirst { it.id == projectId }
        if (pi < 0) return
        val project = projects[pi]
        val si = project.sentences.indexOfFirst { it.id == sentenceId }
        if (si < 0 || text.isBlank()) return
        project.sentences[si] = project.sentences[si].copy(text = text.trim())
        save(context, projects)
    }

    fun deleteSentence(
        context: Context,
        projects: MutableList<Project>,
        projectId: String,
        sentenceId: String,
    ) {
        val pi = projects.indexOfFirst { it.id == projectId }
        if (pi < 0) return
        val project = projects[pi]
        val si = project.sentences.indexOfFirst { it.id == sentenceId }
        if (si < 0) return
        project.sentences[si].wav?.let { File(projectDir(context, projectId), it).delete() }
        project.sentences.removeAt(si)
        save(context, projects)
    }

    fun fileFor(context: Context, projectId: String, sentence: Sentence): File =
        File(projectDir(context, projectId), sentence.wav ?: "")

    // ---------------------------------------------------------------- 导出

    fun scriptText(project: Project): String =
        project.sentences.joinToString("\n") { it.text } + "\n"

    fun metadataJsonl(project: Project): String {
        val sb = StringBuilder()
        project.sentences.forEach { s ->
            if (!s.recorded) return@forEach
            val charsArr = JSONArray()
            s.chars.forEach { c ->
                charsArr.put(JSONArray().put(c.ch).put(c.startMs).put(c.endMs).put(c.wrong))
            }
            val obj = JSONObject().apply {
                put("file", s.wav)
                put("text", s.text)
                put("durationMs", s.durationMs)
                put("sampleRate", s.sampleRate)
                put("device", s.deviceName)
                put("recordedAt", s.recordedAt)
                put("rate", s.rate.toDouble())
                put("chars", charsArr)
            }
            sb.append(obj.toString()).append('\n')
        }
        return sb.toString()
    }

    /** 导出给切字用的逐字时间轴（独立 alignment.json）。 */
    fun alignmentJson(project: Project): String {
        val root = JSONArray()
        project.sentences.forEach { s ->
            if (!s.recorded) return@forEach
            val charsArr = JSONArray()
            s.chars.forEach { c ->
                charsArr.put(
                    JSONObject().apply {
                        put("ch", c.ch)
                        put("startMs", c.startMs)
                        put("endMs", c.endMs)
                        put("wrong", c.wrong)
                    },
                )
            }
            root.put(
                JSONObject().apply {
                    put("file", s.wav)
                    put("text", s.text)
                    put("durationMs", s.durationMs)
                    put("rate", s.rate.toDouble())
                    put("chars", charsArr)
                },
            )
        }
        return root.toString(2)
    }

    fun zipProject(context: Context, project: Project): File {
        val dir = projectDir(context, project.id)
        val exportDir = File(context.cacheDir, "exports").apply { mkdirs() }
        val safeName = project.name.replace(Regex("""[\\/:*?"<>|\r\n]"""), "_").take(40).ifBlank { "project" }
        val zip = File(exportDir, "$safeName.zip")
        if (zip.exists()) zip.delete()
        ZipOutputStream(zip.outputStream().buffered()).use { zos ->
            project.sentences.forEach { s ->
                val rel = s.wav ?: return@forEach
                val file = File(dir, rel)
                if (!file.isFile) return@forEach
                zos.putNextEntry(ZipEntry(rel))
                file.inputStream().use { it.copyTo(zos) }
                zos.closeEntry()
            }
            zos.putNextEntry(ZipEntry("metadata.jsonl"))
            zos.write(metadataJsonl(project).toByteArray(Charsets.UTF_8))
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("alignment.json"))
            zos.write(alignmentJson(project).toByteArray(Charsets.UTF_8))
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("script.txt"))
            zos.write(scriptText(project).toByteArray(Charsets.UTF_8))
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("project.json"))
            zos.write(
                JSONObject().apply {
                    put("id", project.id)
                    put("name", project.name)
                    put("createdAt", project.createdAt)
                }.toString().toByteArray(Charsets.UTF_8),
            )
            zos.closeEntry()
        }
        return zip
    }
}
