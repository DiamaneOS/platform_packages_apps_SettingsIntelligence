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

import android.app.Activity;
import android.graphics.Insets;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsAnimation;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.android.settings.intelligence.R;

import java.util.List;

/**
 * Lays the Tally search panel (search_panel.xml) out edge to edge: the bar right under the
 * status bar, and the search slot 4 dp above the navigation bar, as on the homepage, or above
 * the keyboard while it shows. While the keyboard opens or closes, the slot and the results
 * follow it frame by frame. A hairline shows under the bar while results are hidden above the
 * list's top.
 */
final class TallySearchPanel extends WindowInsetsAnimation.Callback
        implements View.OnApplyWindowInsetsListener {

    private final View mPanel;
    private WindowInsets mLastInsets;
    private boolean mImeAnimating;

    private TallySearchPanel(View panel) {
        super(DISPATCH_MODE_STOP);
        mPanel = panel;
    }

    /** Sets up the panel inflated from search_panel.xml, with its results list. */
    static void attach(@NonNull Activity activity, @NonNull View panel,
            @NonNull RecyclerView list) {
        // The panel keeps itself clear of the system bars and the keyboard, whatever the target
        // SDK makes of the window.
        activity.getWindow().setDecorFitsSystemWindows(false);
        final TallySearchPanel insets = new TallySearchPanel(panel);
        panel.setOnApplyWindowInsetsListener(insets);
        panel.setWindowInsetsAnimationCallback(insets);

        final View divider = panel.findViewById(R.id.tally_search_bar_divider);
        if (divider == null) {
            return;
        }
        // The list reports scrolls, and a layout that changes its children as a scroll of 0.
        list.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                updateDivider(divider, recyclerView);
            }
        });
        list.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight,
                oldBottom) -> updateDivider(divider, list));
    }

    private static void updateDivider(View divider, RecyclerView list) {
        divider.setVisibility(list.canScrollVertically(-1) ? View.VISIBLE : View.INVISIBLE);
    }

    @Override
    public WindowInsets onApplyWindowInsets(View view, WindowInsets insets) {
        mLastInsets = insets;
        // While the keyboard moves, onProgress() lays the panel out; this is its end state.
        if (!mImeAnimating) {
            applyPadding(insets);
        }
        return WindowInsets.CONSUMED;
    }

    @Override
    public void onPrepare(@NonNull WindowInsetsAnimation animation) {
        if (isIme(animation)) {
            mImeAnimating = true;
        }
    }

    @NonNull
    @Override
    public WindowInsets onProgress(@NonNull WindowInsets insets,
            @NonNull List<WindowInsetsAnimation> runningAnimations) {
        if (mImeAnimating) {
            applyPadding(insets);
        }
        return insets;
    }

    @Override
    public void onEnd(@NonNull WindowInsetsAnimation animation) {
        if (isIme(animation)) {
            mImeAnimating = false;
            if (mLastInsets != null) {
                applyPadding(mLastInsets);
            }
        }
    }

    private static boolean isIme(WindowInsetsAnimation animation) {
        return (animation.getTypeMask() & WindowInsets.Type.ime()) != 0;
    }

    private void applyPadding(WindowInsets insets) {
        final Insets bars = insets.getInsets(
                WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
        final Insets ime = insets.getInsets(WindowInsets.Type.ime());
        mPanel.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, ime.bottom));
    }
}
