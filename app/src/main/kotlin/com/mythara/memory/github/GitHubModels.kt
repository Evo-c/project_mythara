package com.mythara.memory.github

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire-level DTOs for the small slice of the GitHub REST API we use:
 *   - GET  /user                       — validate PAT
 *   - GET  /repos/{owner}/{repo}       — confirm repo exists
 *   - POST /user/repos                 — create the memory repo if missing
 *   - GET  /repos/{owner}/{repo}/contents/{path} — read file + sha
 *   - PUT  /repos/{owner}/{repo}/contents/{path} — write file (create or update)
 *
 * Only fields we actually consume are modelled. `@JsonIgnoreUnknownKeys`
 * via the shared Json config lets the rest of GitHub's response evolve
 * without breaking us.
 */

@Serializable
data class UserResponse(
    val login: String,
    val id: Long? = null,
    val name: String? = null,
)

@Serializable
data class RepoResponse(
    val name: String,
    @SerialName("full_name") val fullName: String,
    val private: Boolean = false,
    @SerialName("default_branch") val defaultBranch: String = "main",
)

@Serializable
data class CreateRepoRequest(
    val name: String,
    val description: String = "Mythara learnings + memory backup. Managed by the Mythara Android app.",
    val private: Boolean = true,
    @SerialName("auto_init") val autoInit: Boolean = true,
)

/**
 * GitHub returns either a single ContentItem (file) or an array of ContentItems
 * (directory). We only ever GET files we wrote, so the single-object form is
 * what we expect.
 */
@Serializable
data class ContentItem(
    val name: String,
    val path: String,
    val sha: String,
    val size: Long = 0,
    val type: String = "file",
    @SerialName("content") val contentBase64: String? = null,
    val encoding: String? = null,
)

@Serializable
data class PutContentRequest(
    val message: String,
    /** Base64-encoded file content. The API requires no line wrapping. */
    val content: String,
    /** Required when *updating* an existing file; omit on first create. */
    val sha: String? = null,
    val branch: String? = null,
)

@Serializable
data class PutContentResponse(
    val content: ContentItem? = null,
    val commit: CommitInfo? = null,
)

@Serializable
data class CommitInfo(
    val sha: String,
    val message: String? = null,
)

@Serializable
data class ErrorBody(
    val message: String,
    @SerialName("documentation_url") val docUrl: String? = null,
    val errors: List<ErrorDetail>? = null,
)

@Serializable
data class ErrorDetail(
    val resource: String? = null,
    val field: String? = null,
    val code: String? = null,
    val message: String? = null,
)
