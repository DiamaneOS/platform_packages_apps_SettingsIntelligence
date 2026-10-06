/*
 * Copyright (C) 2026 The DiamaneOS Project
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
 *
 */

package com.android.settings.intelligence.search;

import static com.android.settings.intelligence.search.TallyHomeSearchContract.ARG_FRESH;
import static com.android.settings.intelligence.search.TallyHomeSearchContract.ARG_QUERY;
import static com.android.settings.intelligence.search.TallyHomeSearchContract.COLUMNS;
import static com.android.settings.intelligence.search.TallyHomeSearchContract.KIND_ACCESSIBILITY;
import static com.android.settings.intelligence.search.TallyHomeSearchContract.KIND_APP;
import static com.android.settings.intelligence.search.TallyHomeSearchContract.KIND_INPUT;
import static com.android.settings.intelligence.search.TallyHomeSearchContract.KIND_PAGE;
import static com.android.settings.intelligence.search.TallyHomeSearchContract.MAX_ROWS;
import static com.android.settings.intelligence.search.TallyHomeSearchContract.PATH_PAGES;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.os.OperationCanceledException;
import android.os.Process;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Log;

import com.android.settings.intelligence.nano.SettingsIntelligenceLogProto.SettingsIntelligenceEvent;
import com.android.settings.intelligence.overlay.FeatureFactory;
import com.android.settings.intelligence.search.query.AccessibilityServiceResultTask;
import com.android.settings.intelligence.search.query.DatabaseResultTask;
import com.android.settings.intelligence.search.query.InputDeviceResultTask;
import com.android.settings.intelligence.search.query.SearchQueryTask;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.PriorityQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Settings results for Home's search, read-only: the rows Settings search shows for the same words,
 * found by the same tasks ({@link SearchFeatureProvider#getSearchQueryTasks}) and in the same order
 * ({@link SearchResultAggregator}): pages from the index ({@link DatabaseResultTask}: enabled pages
 * only, so what the device or the user cannot use stays out), then installed apps' app info,
 * accessibility services and keyboards, each by the rules Settings search applies. Rows carry no
 * summaries (as Settings search shows them), no icons and no intents. A result opens through
 * {@link TallyHomeSearchActivity}.
 *
 * <p>App info results name installed apps; the only caller, Launcher, already knows them.
 *
 * <p>Only holders of {@link TallyHomeSearchContract#PERMISSION} (signature) reach it, only from
 * this user. It keeps nothing: no saved query, no log of the words.
 */
public class TallyHomeSearchProvider extends ContentProvider {

    private static final String TAG = "TallyHomeSearch";

    /** How long a query waits for the index to be brought up to date. */
    private static final long MAX_INDEX_WAIT_MS = 10_000;
    /** How often a waiting query checks whether it was cancelled. */
    private static final long WAIT_SLICE_MS = 100;
    /** A fresh query refreshes the index at most this often. */
    private static final long MIN_REFRESH_INTERVAL_MS = 5_000;
    /** How long each task may take, as SearchResultAggregator.SHORT_CHECK_TASK_TIMEOUT_MS. */
    private static final long TASK_TIMEOUT_MS = 600;

    private static final Object sLock = new Object();
    /** Counts down when the running refresh is done; null when none runs. */
    private static CountDownLatch sRefresh;
    /** When the last refresh started (elapsed realtime), 0 before the first. */
    private static long sLastRefreshStart;

    private Handler mMainHandler;

    @Override
    public boolean onCreate() {
        mMainHandler = new Handler(Looper.getMainLooper());
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, Bundle queryArgs,
            CancellationSignal cancellationSignal) {
        final MatrixCursor cursor = new MatrixCursor(COLUMNS);
        if (!isPagesUri(uri) || !Binder.getCallingUserHandle().equals(Process.myUserHandle())) {
            return cursor;
        }
        final String query = TallyHomeSearchContract.cleanQuery(
                queryArgs == null ? null : queryArgs.getString(ARG_QUERY));
        if (query == null) {
            return cursor;
        }
        final boolean fresh = queryArgs.getBoolean(ARG_FRESH, false);

        final long identity = Binder.clearCallingIdentity();
        List<SearchQueryTask> tasks = Collections.emptyList();
        try {
            awaitIndex(fresh, cancellationSignal);
            final Context context = getContext();
            final SearchFeatureProvider search =
                    FeatureFactory.get(context).searchFeatureProvider();
            // Settings search's own tasks, which also clean the words as it does.
            tasks = search.getSearchQueryTasks(context, query);
            final ExecutorService executor = search.getExecutorService();
            for (SearchQueryTask task : tasks) {
                executor.execute(task);
            }
            int id = 0;
            for (Found found : collect(tasks)) {
                if (id >= MAX_ROWS) {
                    break;
                }
                if (cancellationSignal != null) {
                    cancellationSignal.throwIfCanceled();
                }
                final SearchResult result = found.result;
                if (result == null || TextUtils.isEmpty(result.title)
                        || TextUtils.isEmpty(result.dataKey) || result.payload == null
                        || result.payload.getIntent() == null) {
                    continue;
                }
                cursor.addRow(new Object[] {
                        id++,
                        result.dataKey,
                        result.title.toString(),
                        TallyHomeSearchContract.parentOf(result.breadcrumbs, result.title),
                        found.kind,
                });
            }
        } catch (InterruptedException e) {
            // Never the words: only that the query failed.
            Log.w(TAG, "Settings results query failed");
        } finally {
            for (SearchQueryTask task : tasks) {
                task.cancel(true /* mayInterruptIfRunning */);
            }
            Binder.restoreCallingIdentity(identity);
        }
        return cursor;
    }

    /** A result and its kind. */
    private static final class Found {
        final SearchResult result;
        final String kind;

        Found(SearchResult result, String kind) {
            this.result = result;
            this.kind = kind;
        }
    }

    /**
     * The results of {@code tasks}, as SearchResultAggregator merges them for Settings search: pages
     * first, in the index's order, then the rest by rank. A task that is slow or fails adds none;
     * one of a kind this provider does not know adds none either.
     */
    private static List<Found> collect(List<SearchQueryTask> tasks) throws InterruptedException {
        final List<Found> merged = new ArrayList<>();
        final PriorityQueue<Found> rest =
                new PriorityQueue<>((a, b) -> a.result.compareTo(b.result));
        for (SearchQueryTask task : tasks) {
            final String kind = kindOf(task.getTaskId());
            List<? extends SearchResult> results;
            try {
                results = task.get(TASK_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            } catch (TimeoutException | ExecutionException | CancellationException e) {
                // Never the words: which task, as Settings search logs it.
                Log.d(TAG, "No results in time from task " + task.getTaskId());
                results = null;
            }
            if (kind == null || results == null) {
                continue;
            }
            for (SearchResult result : results) {
                if (result == null) {
                    continue;
                }
                if (KIND_PAGE.equals(kind)) {
                    merged.add(new Found(result, kind));
                } else {
                    rest.add(new Found(result, kind));
                }
            }
        }
        while (!rest.isEmpty()) {
            merged.add(rest.poll());
        }
        return merged;
    }

    /** The kind of the results of the task with {@code taskId}, or null for a task not known here. */
    private static String kindOf(int taskId) {
        if (taskId == DatabaseResultTask.QUERY_WORKER_ID) {
            return KIND_PAGE;
        } else if (taskId == SettingsIntelligenceEvent.SEARCH_QUERY_INSTALLED_APPS) {
            return KIND_APP;
        } else if (taskId == AccessibilityServiceResultTask.QUERY_WORKER_ID) {
            return KIND_ACCESSIBILITY;
        } else if (taskId == InputDeviceResultTask.QUERY_WORKER_ID) {
            return KIND_INPUT;
        }
        return null;
    }

    private static boolean isPagesUri(Uri uri) {
        final List<String> segments = uri == null ? null : uri.getPathSegments();
        return segments != null && segments.size() == 1 && PATH_PAGES.equals(segments.get(0));
    }

    /**
     * Brings the index up to date before a query, as Settings search does when it opens: on the
     * first query since this process started, and on a fresh one (Home's search just opened) at
     * most every {@link #MIN_REFRESH_INTERVAL_MS}. Waits for a refresh that runs, at most
     * {@link #MAX_INDEX_WAIT_MS}; the refresh runs on Settings search's own serial indexing task,
     * so the two never index at once.
     */
    private void awaitIndex(boolean fresh, CancellationSignal signal)
            throws InterruptedException {
        final CountDownLatch refresh;
        synchronized (sLock) {
            final long now = SystemClock.elapsedRealtime();
            if (sRefresh == null && (sLastRefreshStart == 0
                    || (fresh && now - sLastRefreshStart >= MIN_REFRESH_INTERVAL_MS))) {
                sLastRefreshStart = now;
                final CountDownLatch started = new CountDownLatch(1);
                sRefresh = started;
                final Context context = getContext();
                mMainHandler.post(() -> FeatureFactory.get(context).searchFeatureProvider()
                        .getIndexingManager(context).indexDatabase(() -> {
                            synchronized (sLock) {
                                if (sRefresh == started) {
                                    sRefresh = null;
                                }
                            }
                            started.countDown();
                        }));
            }
            refresh = sRefresh;
        }
        if (refresh == null) {
            return;
        }
        final long deadline = SystemClock.elapsedRealtime() + MAX_INDEX_WAIT_MS;
        while (!refresh.await(WAIT_SLICE_MS, TimeUnit.MILLISECONDS)) {
            if (signal != null && signal.isCanceled()) {
                throw new OperationCanceledException();
            }
            if (SystemClock.elapsedRealtime() >= deadline) {
                // Answer from the index as it is; a later query waits again.
                return;
            }
        }
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs,
            String sortOrder) {
        // Only the form with query arguments: the words never go in a selection or a URI.
        return new MatrixCursor(COLUMNS);
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("Read-only");
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("Read-only");
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("Read-only");
    }
}
