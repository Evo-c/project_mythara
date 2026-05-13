package com.mythara.memory.github

import android.util.Base64
import android.util.Log
import com.mythara.minimax.MiniMaxClient
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit

/**
 * High-level GitHub-API wrapper. Builds a Retrofit instance per-PAT
 * (because the auth header rides as an interceptor) and exposes the
 * five operations Mythara's memory sync actually performs:
 *
 *   - [validateToken]    — confirms PAT works (`GET /user`)
 *   - [ensureRepo]       — checks/creates the memory repo
 *   - [readFile]         — fetches a file's content + sha
 *   - [writeFile]        — creates or updates a file, returns new sha
 *   - [exists]           — file existence shortcut (for first-time bootstrap)
 *
 * Each call returns a sealed [Outcome] so callers don't have to inspect
 * HTTP codes — `Ok` / `NotFound` / `Unauthorized` / `Conflict` / `Error`.
 */
class GitHubClient(private val pat: String) {

    private val authInterceptor = Interceptor { chain ->
        val req = chain.request().newBuilder()
            .addHeader("Authorization", "Bearer $pat")
            .addHeader("Accept", "application/vnd.github+json")
            .addHeader("X-GitHub-Api-Version", "2022-11-28")
            .addHeader("User-Agent", "Mythara/0.0.1 (Android)")
            .build()
        chain.proceed(req)
    }

    private val logging = HttpLoggingInterceptor().apply {
        level = HttpLoggingInterceptor.Level.BASIC
        redactHeader("Authorization")
    }

    private val ok: OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(authInterceptor)
        .addInterceptor(logging)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val api: GitHubApi = Retrofit.Builder()
        .baseUrl(BASE_URL)
        .client(ok)
        .addConverterFactory(JSON.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(GitHubApi::class.java)

    sealed interface Outcome<out T> {
        data class Ok<T>(val value: T) : Outcome<T>
        data class NotFound(val message: String) : Outcome<Nothing>
        data class Unauthorized(val message: String) : Outcome<Nothing>
        data class Conflict(val message: String) : Outcome<Nothing>
        data class Error(val httpStatus: Int, val message: String) : Outcome<Nothing>
    }

    suspend fun validateToken(): Outcome<String> {
        val r = api.whoAmI()
        return when {
            r.isSuccessful -> Outcome.Ok(r.body()?.login.orEmpty())
            r.code() == 401 -> Outcome.Unauthorized(readErr(r) ?: "Token rejected by GitHub.")
            else -> Outcome.Error(r.code(), readErr(r) ?: "GitHub error ${r.code()}")
        }
    }

    suspend fun ensureRepo(owner: String, repo: String, createIfMissing: Boolean = true): Outcome<RepoResponse> {
        val r = api.getRepo(owner, repo)
        return when {
            r.isSuccessful -> Outcome.Ok(r.body()!!)
            r.code() == 404 && createIfMissing -> {
                Log.d(TAG, "repo $owner/$repo missing; attempting POST /user/repos")
                val c = api.createRepo(CreateRepoRequest(name = repo))
                when {
                    c.isSuccessful -> Outcome.Ok(c.body()!!)
                    c.code() == 401 -> Outcome.Unauthorized(readErr(c) ?: "Need `repo` scope to create.")
                    c.code() == 422 -> Outcome.Conflict(readErr(c) ?: "Repo name already taken or invalid.")
                    else -> Outcome.Error(c.code(), readErr(c) ?: "create-repo failed (${c.code()})")
                }
            }
            r.code() == 404 -> Outcome.NotFound("Repo $owner/$repo not found")
            r.code() == 401 -> Outcome.Unauthorized(readErr(r) ?: "Auth failed.")
            else -> Outcome.Error(r.code(), readErr(r) ?: "GitHub error ${r.code()}")
        }
    }

    suspend fun readFile(owner: String, repo: String, path: String): Outcome<FileContent> {
        val r = api.getContents(owner, repo, path)
        return when {
            r.isSuccessful -> {
                val item = r.body()!!
                val decoded = item.contentBase64
                    ?.replace("\n", "")
                    ?.let { Base64.decode(it, Base64.DEFAULT).toString(Charsets.UTF_8) }
                    .orEmpty()
                Outcome.Ok(FileContent(sha = item.sha, text = decoded))
            }
            r.code() == 404 -> Outcome.NotFound(path)
            r.code() == 401 -> Outcome.Unauthorized(readErr(r) ?: "Auth failed.")
            else -> Outcome.Error(r.code(), readErr(r) ?: "GET contents failed (${r.code()})")
        }
    }

    suspend fun exists(owner: String, repo: String, path: String): Boolean {
        val r = api.getContents(owner, repo, path)
        return r.isSuccessful
    }

    /**
     * Create or update a file. If [previousSha] is null we attempt to
     * create; on 422 ("sha is required") we fall back to GETting the
     * current sha and retrying with it. This handles the case where
     * our local manifest cache went stale (different device wrote the
     * file in between our sync runs).
     *
     * For multi-device scenarios use [writeFileMerging] instead — it
     * adds a remote/local merge step so concurrent JSONL appends don't
     * clobber each other.
     */
    suspend fun writeFile(
        owner: String,
        repo: String,
        path: String,
        text: String,
        commitMessage: String,
        branch: String,
        previousSha: String? = null,
    ): Outcome<FileContent> {
        val base64 = Base64.encodeToString(text.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        val attempt = api.putContents(
            owner, repo, path,
            PutContentRequest(message = commitMessage, content = base64, sha = previousSha, branch = branch),
        )
        return when {
            attempt.isSuccessful -> {
                val sha = attempt.body()?.content?.sha.orEmpty()
                Outcome.Ok(FileContent(sha = sha, text = text))
            }
            attempt.code() == 422 && previousSha == null -> {
                // File probably exists already; fetch its sha and retry.
                Log.d(TAG, "PUT $path returned 422; re-attempting with current sha")
                val current = readFile(owner, repo, path)
                if (current is Outcome.Ok) {
                    val retry = api.putContents(
                        owner, repo, path,
                        PutContentRequest(message = commitMessage, content = base64, sha = current.value.sha, branch = branch),
                    )
                    if (retry.isSuccessful) {
                        Outcome.Ok(FileContent(sha = retry.body()?.content?.sha.orEmpty(), text = text))
                    } else {
                        Outcome.Error(retry.code(), readErr(retry) ?: "retry failed (${retry.code()})")
                    }
                } else {
                    Outcome.Error(422, "PUT 422 and re-read failed")
                }
            }
            attempt.code() == 401 -> Outcome.Unauthorized(readErr(attempt) ?: "Auth failed.")
            attempt.code() == 409 -> Outcome.Conflict(readErr(attempt) ?: "Concurrent write — try sync again.")
            else -> Outcome.Error(attempt.code(), readErr(attempt) ?: "PUT contents failed (${attempt.code()})")
        }
    }

    /**
     * Like [writeFile] but conflict-aware. On a sha-mismatch failure
     * (422 with stale sha, or 409 — both indicate another writer landed
     * a commit between our last read and our PUT), this:
     *
     *   1. Re-fetches the remote file
     *   2. Calls [merge] to combine remote + local content
     *   3. Retries the PUT with the merged body + the remote's fresh sha
     *
     * One retry only — a second 409 means three-way contention which
     * the caller should surface, not silently loop. The merge function
     * is the strategy: JSONL files want a line-union by ID; manifests
     * want a per-key newest-wins merge; single-author files (README,
     * settings) can pass `{ _, local -> local }` to keep their local
     * version and just refresh the sha.
     */
    suspend fun writeFileMerging(
        owner: String,
        repo: String,
        path: String,
        text: String,
        commitMessage: String,
        branch: String,
        previousSha: String? = null,
        merge: (remote: String, local: String) -> String,
    ): Outcome<FileContent> {
        // First pass: optimistic, using the sha the caller has cached.
        val first = writeFile(
            owner = owner, repo = repo, path = path,
            text = text, commitMessage = commitMessage,
            branch = branch, previousSha = previousSha,
        )
        if (first !is Outcome.Conflict && first !is Outcome.Error) return first
        // The above writeFile already retried 422-with-null-sha by
        // refetching, so we only land here on 409 or a non-recoverable
        // error. Treat 409 as merge-and-retry.
        val needsMerge = (first is Outcome.Conflict) ||
            (first is Outcome.Error && first.httpStatus == 409)
        if (!needsMerge) return first

        Log.d(TAG, "merging $path — concurrent write detected, fetching remote")
        val remote = readFile(owner, repo, path)
        if (remote !is Outcome.Ok) {
            // Remote was deleted mid-race, or we lost auth. Fall back to
            // a plain PUT with no sha (create semantics).
            return writeFile(
                owner = owner, repo = repo, path = path,
                text = text, commitMessage = commitMessage,
                branch = branch, previousSha = null,
            )
        }
        val merged = runCatching { merge(remote.value.text, text) }.getOrElse {
            return Outcome.Error(
                httpStatus = -1,
                message = "merge failed for $path: ${it.message}",
            )
        }
        val mergedBase64 = Base64.encodeToString(merged.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        val retry = api.putContents(
            owner, repo, path,
            PutContentRequest(
                message = "$commitMessage (merged)",
                content = mergedBase64,
                sha = remote.value.sha,
                branch = branch,
            ),
        )
        return when {
            retry.isSuccessful -> Outcome.Ok(
                FileContent(sha = retry.body()?.content?.sha.orEmpty(), text = merged),
            )
            retry.code() == 409 -> Outcome.Conflict(
                "Three-way race on $path — try sync again in a moment.",
            )
            retry.code() == 401 -> Outcome.Unauthorized(readErr(retry) ?: "Auth failed mid-merge.")
            else -> Outcome.Error(retry.code(), readErr(retry) ?: "merge retry failed (${retry.code()})")
        }
    }

    private fun readErr(r: Response<*>): String? {
        val body = r.errorBody()?.string()?.takeIf { it.isNotBlank() } ?: return null
        return runCatching { JSON.decodeFromString<ErrorBody>(body).message }.getOrNull() ?: body.take(200)
    }

    @Serializable
    data class FileContent(val sha: String, val text: String)

    companion object {
        private const val TAG = "Mythara/Memory"
        const val BASE_URL = "https://api.github.com/"
        val JSON: Json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            isLenient = true
            explicitNulls = false
        }
        @Suppress("unused") private val unused = MiniMaxClient::class // shared serializer style reference
    }
}
