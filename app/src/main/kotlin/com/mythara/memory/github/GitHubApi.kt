package com.mythara.memory.github

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.PUT
import retrofit2.http.Path

/**
 * Retrofit surface for the minimal slice of the GitHub REST API Mythara
 * uses for memory sync. Base URL is `https://api.github.com/`. Auth is
 * `Authorization: Bearer <PAT>` injected via interceptor in
 * [GitHubClient]; we deliberately don't put `@Header("Authorization")`
 * on every method so callers can't accidentally omit it.
 */
interface GitHubApi {

    @GET("user")
    suspend fun whoAmI(): Response<UserResponse>

    @GET("repos/{owner}/{repo}")
    suspend fun getRepo(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
    ): Response<RepoResponse>

    @retrofit2.http.POST("user/repos")
    suspend fun createRepo(@Body body: CreateRepoRequest): Response<RepoResponse>

    @GET("repos/{owner}/{repo}/contents/{path}")
    suspend fun getContents(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Path("path", encoded = true) path: String,
    ): Response<ContentItem>

    @PUT("repos/{owner}/{repo}/contents/{path}")
    suspend fun putContents(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Path("path", encoded = true) path: String,
        @Body body: PutContentRequest,
    ): Response<PutContentResponse>
}
