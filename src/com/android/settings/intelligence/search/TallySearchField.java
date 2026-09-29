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

import android.content.Context;
import android.graphics.Rect;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.SearchView;

import com.android.settings.intelligence.R;

/**
 * The search panel's field drawn as the Tally search slot (search_panel.xml). It stands in for
 * the {@link SearchView} the panel used and does what that did: the listener hears every change
 * of the query, and a submit from the keyboard's search key or Enter when the field holds more
 * than spaces; the clear key, shown while there is a query, empties the field and keeps the
 * keyboard up; and the keyboard opens by itself when the window gains focus with the field
 * focused, as SearchView's own field does. A tap anywhere on the slot focuses the field.
 */
public class TallySearchField extends LinearLayout {

    private QueryText mQueryText;
    private View mClearButton;
    private SearchView.OnQueryTextListener mListener;
    private CharSequence mOldQueryText;

    public TallySearchField(Context context) {
        this(context, null);
    }

    public TallySearchField(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public TallySearchField(Context context, AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    public TallySearchField(Context context, AttributeSet attrs, int defStyleAttr,
            int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mQueryText = findViewById(R.id.tally_search_query);
        mClearButton = findViewById(R.id.tally_search_clear);
        mQueryText.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                TallySearchField.this.onTextChanged(s);
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });
        mQueryText.setOnEditorActionListener((v, actionId, event) -> {
            onSubmitQuery();
            return true;
        });
        mClearButton.setOnClickListener(v -> onClearClicked());
        updateClearButton();
    }

    /** Sets the listener for query changes and submits, as {@link SearchView} does. */
    public void setOnQueryTextListener(SearchView.OnQueryTextListener listener) {
        mListener = listener;
    }

    /**
     * Replaces the query in the field and optionally submits it, as
     * {@link SearchView#setQuery(CharSequence, boolean)} does.
     */
    public void setQuery(CharSequence query, boolean submit) {
        mQueryText.setText(query);
        if (query != null) {
            mQueryText.setSelection(mQueryText.length());
        }
        if (submit && !TextUtils.isEmpty(query)) {
            onSubmitQuery();
        }
    }

    @Override
    public boolean requestFocus(int direction, Rect previouslyFocusedRect) {
        // As SearchView: the query field takes the focus.
        return mQueryText.requestFocus(direction, previouslyFocusedRect);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        // The whole 48 dp slot is the field's touch target: a tap on the icon or around the
        // words focuses the field, as a tap on the words does.
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                return true;
            case MotionEvent.ACTION_UP:
                if (event.getX() >= 0 && event.getX() < getWidth()
                        && event.getY() >= 0 && event.getY() < getHeight()) {
                    performClick();
                }
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }

    @Override
    public boolean performClick() {
        super.performClick();
        mQueryText.requestFocus();
        mQueryText.setImeVisibility(true);
        return true;
    }

    private void onTextChanged(CharSequence newText) {
        updateClearButton();
        if (mListener != null && !TextUtils.equals(newText, mOldQueryText)) {
            mListener.onQueryTextChange(newText.toString());
        }
        mOldQueryText = newText.toString();
    }

    private void onSubmitQuery() {
        final CharSequence query = mQueryText.getText();
        if (query != null && TextUtils.getTrimmedLength(query) > 0) {
            if (mListener == null || !mListener.onQueryTextSubmit(query.toString())) {
                mQueryText.setImeVisibility(false);
            }
        }
    }

    private void onClearClicked() {
        mQueryText.setText("");
        mQueryText.requestFocus();
        mQueryText.setImeVisibility(true);
    }

    private void updateClearButton() {
        mClearButton.setVisibility(TextUtils.isEmpty(mQueryText.getText()) ? GONE : VISIBLE);
    }

    /**
     * The query field. It asks for the keyboard as SearchView's field does: once it has window
     * focus and is focused, and only after the input method has connected to it.
     */
    public static class QueryText extends EditText {

        private boolean mHasPendingShowSoftInputRequest;
        private final Runnable mRunShowSoftInputIfNecessary = this::showSoftInputIfNecessary;

        public QueryText(Context context) {
            super(context);
        }

        public QueryText(Context context, AttributeSet attrs) {
            super(context, attrs);
        }

        public QueryText(Context context, AttributeSet attrs, int defStyleAttr) {
            super(context, attrs, defStyleAttr);
        }

        public QueryText(Context context, AttributeSet attrs, int defStyleAttr,
                int defStyleRes) {
            super(context, attrs, defStyleAttr, defStyleRes);
        }

        @Override
        public void onWindowFocusChanged(boolean hasWindowFocus) {
            super.onWindowFocusChanged(hasWindowFocus);
            if (hasWindowFocus && hasFocus() && getVisibility() == VISIBLE) {
                // Too early to ask for the keyboard: wait for onCreateInputConnection().
                mHasPendingShowSoftInputRequest = true;
            }
        }

        @Override
        public InputConnection onCreateInputConnection(EditorInfo editorInfo) {
            final InputConnection ic = super.onCreateInputConnection(editorInfo);
            if (mHasPendingShowSoftInputRequest) {
                removeCallbacks(mRunShowSoftInputIfNecessary);
                post(mRunShowSoftInputIfNecessary);
            }
            return ic;
        }

        private void showSoftInputIfNecessary() {
            if (mHasPendingShowSoftInputRequest) {
                getContext().getSystemService(InputMethodManager.class).showSoftInput(this, 0);
                mHasPendingShowSoftInputRequest = false;
            }
        }

        void setImeVisibility(boolean visible) {
            final InputMethodManager imm = getContext().getSystemService(InputMethodManager.class);
            if (!visible) {
                mHasPendingShowSoftInputRequest = false;
                removeCallbacks(mRunShowSoftInputIfNecessary);
                imm.hideSoftInputFromWindow(getWindowToken(), 0);
                return;
            }
            if (imm.isActive(this)) {
                // Already connected to the input method, so the request passes its focus check.
                mHasPendingShowSoftInputRequest = false;
                removeCallbacks(mRunShowSoftInputIfNecessary);
                imm.showSoftInput(this, 0);
                return;
            }
            // Otherwise ask once the input method has connected (onCreateInputConnection()).
            mHasPendingShowSoftInputRequest = true;
        }
    }
}
