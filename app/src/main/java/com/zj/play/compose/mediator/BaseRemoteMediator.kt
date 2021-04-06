/*
 * Copyright (C) 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.zj.play.compose.mediator

import android.util.Log
import androidx.paging.ExperimentalPagingApi
import androidx.paging.LoadType
import androidx.paging.PagingState
import androidx.paging.RemoteMediator
import androidx.room.withTransaction
import com.zj.core.util.DataStoreUtils
import com.zj.model.room.PlayDatabase
import com.zj.model.room.entity.Article
import com.zj.model.room.entity.RemoteKeys
import retrofit2.HttpException
import java.io.IOException
import java.util.concurrent.TimeUnit

// GitHub page API is 1 based: https://developer.github.com/v3/#pagination
private const val PLAY_ANDROID_STARTING_PAGE_INDEX = 0

@OptIn(ExperimentalPagingApi::class)
abstract class BaseRemoteMediator(
    private val localType: Int,
    private val repoDatabase: PlayDatabase
) : RemoteMediator<Int, Article>() {

    companion object {
        private const val TAG = "ArticleRemoteMediator"
        private const val LAST_UPDATED = "LAST_UPDATED"
    }

    override suspend fun initialize(): InitializeAction {
        // Launch remote refresh as soon as paging starts and do not trigger remote prepend or
        // append until refresh has succeeded. In cases where we don't mind showing out-of-date,
        // cached offline data, we can return SKIP_INITIAL_REFRESH instead to prevent paging
        // triggering remote refresh.
        return InitializeAction.LAUNCH_INITIAL_REFRESH
//        val dataStore = DataStoreUtils
//
//        val cacheTimeout = TimeUnit.HOURS.convert(1, TimeUnit.MILLISECONDS)
//        return if (System.currentTimeMillis() - dataStore.getSyncData(
//                LAST_UPDATED,
//                0L
//            ) >= cacheTimeout
//        ) {
//            // Cached data is up-to-date, so there is no need to re-fetch
//            // from the network.
//            InitializeAction.SKIP_INITIAL_REFRESH
//        } else {
//            // Need to refresh cached data from network; returning
//            // LAUNCH_INITIAL_REFRESH here will also block RemoteMediator's
//            // APPEND and PREPEND from running until REFRESH succeeds.
//            InitializeAction.LAUNCH_INITIAL_REFRESH
//        }

    }

    override suspend fun load(
        loadType: LoadType,
        state: PagingState<Int, Article>
    ): MediatorResult {

        val page = when (loadType) {
            LoadType.REFRESH -> null
            LoadType.PREPEND -> {
                val remoteKeys = getRemoteKeyForFirstItem(state)
                // If the previous key is null, then the list is empty so we should wait for data
                // fetched by remote refresh and can simply skip loading this time by returning
                // `false` for endOfPaginationReached.
                remoteKeys?.prevKey ?: return MediatorResult.Success(endOfPaginationReached = false)
            }
            LoadType.APPEND -> {
                val remoteKeys = getRemoteKeyForLastItem(state)
                // If the next key is null, then the list is empty so we should wait for data
                // fetched by remote refresh and can simply skip loading this time by returning
                // `false` for endOfPaginationReached.
                remoteKeys?.nextKey ?: return MediatorResult.Success(endOfPaginationReached = false)
            }
        } ?: PLAY_ANDROID_STARTING_PAGE_INDEX

        Log.e(TAG, "load: localType:$localType  page:$page")
        try {
            val repos = getArticleList(page)
            val endOfPaginationReached = repos.isEmpty()
            Log.e(TAG, "load: localType:$localType  repos:${repos.size}")
            repoDatabase.withTransaction {
                // clear all tables in the database
                if (loadType == LoadType.REFRESH) {
                    repoDatabase.remoteKeysDao().clearRemoteKeys()
                    repoDatabase.browseHistoryDao().clearRepos()
                }
                val prevKey = if (page == PLAY_ANDROID_STARTING_PAGE_INDEX) null else page - 1
                val nextKey = if (endOfPaginationReached) null else page + 1
                val keys = repos.map {
                    it.localType = localType
                    repoDatabase.browseHistoryDao().deleteById(it.id)
                    RemoteKeys(
                        repoId = it.id,
                        prevKey = prevKey,
                        nextKey = nextKey,
                        localType = localType
                    )
                }

                val remoteLong = repoDatabase.remoteKeysDao().insertAll(keys)
                val articleLong = repoDatabase.browseHistoryDao().insertList(repos)
                val dataStore = DataStoreUtils
                dataStore.putSyncData(LAST_UPDATED, System.currentTimeMillis())
                Log.e(
                    TAG,
                    "load: localType:$localType  remoteLong:$remoteLong     articleLong:$articleLong"
                )
            }
            return MediatorResult.Success(endOfPaginationReached = endOfPaginationReached)
        } catch (exception: IOException) {
            return MediatorResult.Error(exception)
        } catch (exception: HttpException) {
            return MediatorResult.Error(exception)
        }
    }

    abstract suspend fun getArticleList(page: Int): List<Article>

    private suspend fun getRemoteKeyForLastItem(state: PagingState<Int, Article>): RemoteKeys? {
        // Get the last page that was retrieved, that contained items.
        // From that last page, get the last item
        return state.pages.lastOrNull { it.data.isNotEmpty() }?.data?.lastOrNull()
            ?.let { repo ->
                // Get the remote keys of the last item retrieved
                repoDatabase.remoteKeysDao().remoteKeysRepoId(repo.id, localType)
            }
    }

    private suspend fun getRemoteKeyForFirstItem(state: PagingState<Int, Article>): RemoteKeys? {
        // Get the first page that was retrieved, that contained items.
        // From that first page, get the first item
        return state.pages.firstOrNull { it.data.isNotEmpty() }?.data?.firstOrNull()
            ?.let { repo ->
                // Get the remote keys of the first items retrieved
                repoDatabase.remoteKeysDao().remoteKeysRepoId(repo.id, localType)
            }
    }

    private suspend fun getRemoteKeyClosestToCurrentPosition(
        state: PagingState<Int, Article>
    ): RemoteKeys? {
        // The paging library is trying to load data after the anchor position
        // Get the item closest to the anchor position
        return state.anchorPosition?.let { position ->
            state.closestItemToPosition(position)?.id?.let { repoId ->
                repoDatabase.remoteKeysDao().remoteKeysRepoId(repoId, localType)
            }
        }
    }
}