/*
 * Copyright 2011 Azwan Adli Abdullah
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.gh4a.activities;

import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Parcelable;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.gh4a.adapter.ItemsWithDescriptionAdapter;
import com.gh4a.utils.ActivityResultHelpers;
import com.google.android.material.appbar.AppBarLayout;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.view.ContextThemeWrapper;
import androidx.coordinatorlayout.widget.CoordinatorLayout;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.FragmentManager;

import android.util.Pair;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import com.gh4a.BaseActivity;
import com.gh4a.Gh4Application;
import com.gh4a.R;
import com.gh4a.ServiceFactory;
import com.gh4a.fragment.ConfirmationDialogFragment;
import com.gh4a.fragment.IssueFragment;
import com.gh4a.utils.ApiHelpers;
import com.gh4a.utils.IntentUtils;
import com.gh4a.utils.RxUtils;
import com.gh4a.utils.SingleFactory;
import com.gh4a.widget.BottomSheetCompatibleScrollingViewBehavior;
import com.gh4a.widget.IssueStateTrackingFloatingActionButton;
import com.meisolsson.githubsdk.model.Issue;
import com.meisolsson.githubsdk.model.IssueState;
import com.meisolsson.githubsdk.model.IssueStateReason;
import com.meisolsson.githubsdk.model.request.issue.IssueRequest;
import com.meisolsson.githubsdk.service.issues.IssueService;

import java.util.ArrayList;
import java.util.Locale;

public class IssueActivity extends BaseActivity implements
        View.OnClickListener, ConfirmationDialogFragment.Callback {
    public static Intent makeIntent(Context context, Issue issue) {
        Pair<String, String> repoOwnerAndName = ApiHelpers.extractRepoOwnerAndNameFromIssue(issue);
        return makeIntent(context, repoOwnerAndName.first, repoOwnerAndName.second, issue.number());
    }

    public static Intent makeIntent(Context context, String login, String repoName, int number) {
        return makeIntent(context, login, repoName, number, null);
    }

    public static Intent makeIntent(Context context, String login, String repoName,
            int number, IntentUtils.InitialCommentMarker initialComment) {
        return new Intent(context, IssueActivity.class)
                .putExtra("owner", login)
                .putExtra("repo", repoName)
                .putExtra("number", number)
                .putExtra("initial_comment", initialComment);
    }

    private static final int ID_LOADER_ISSUE = 0;
    private static final int ID_LOADER_COLLABORATOR_STATUS = 1;

    private final ActivityResultLauncher<Intent> mEditIssueLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            new ActivityResultHelpers.ActivityResultSuccessCallback(() -> {
                onRefresh();
                setResult(RESULT_OK);
            }));

    private Issue mIssue;
    private String mRepoOwner;
    private String mRepoName;
    private int mIssueNumber;
    private IntentUtils.InitialCommentMarker mInitialComment;
    private ViewGroup mHeader;
    private Boolean mIsCollaborator;
    private IssueStateTrackingFloatingActionButton mEditFab;
    private final Handler mHandler = new Handler();
    private IssueFragment mFragment;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.frame_layout);
        setContentShown(false);

        LayoutInflater inflater =
                LayoutInflater.from(new ContextThemeWrapper(this, R.style.HeaderTheme));
        mHeader = (ViewGroup) inflater.inflate(R.layout.issue_header, null);
        mHeader.setClickable(false);
        mHeader.setVisibility(View.GONE);
        addHeaderView(mHeader, false);

        setToolbarScrollable(true);
        loadIssue(false);
        loadCollaboratorStatus(false);
    }

    @NonNull
    protected String getActionBarTitle() {
        return getString(R.string.issue) + " #" + mIssueNumber;
    }

    @Nullable
    @Override
    protected String getActionBarSubtitle() {
        return mRepoOwner + "/" + mRepoName;
    }

    @Override
    protected AppBarLayout.ScrollingViewBehavior onCreateSwipeLayoutBehavior() {
        return new BottomSheetCompatibleScrollingViewBehavior();
    }

    @Override
    protected void onInitExtras(Bundle extras) {
        super.onInitExtras(extras);
        mRepoOwner = extras.getString("owner");
        mRepoName = extras.getString("repo");
        mIssueNumber = extras.getInt("number");
        mInitialComment = extras.getParcelable("initial_comment");
        extras.remove("initial_comment");
    }

    private void showUiIfDone() {
        if (mIssue == null || mIsCollaborator == null) {
            return;
        }
        FragmentManager fm = getSupportFragmentManager();
        var existingFragment = (IssueFragment) fm.findFragmentById(R.id.details);
        if (existingFragment != null) {
            setFragment(existingFragment);
        } else {
            IssueFragment newFragment = IssueFragment.newInstance(mRepoOwner, mRepoName,
                    mIssue, mIsCollaborator, mInitialComment);
            setFragment(newFragment);
            fm.beginTransaction()
                    .add(R.id.details, newFragment)
                    .commitAllowingStateLoss();
            mInitialComment = null;
        }

        updateHeader();
        updateFabVisibility();
        setContentShown(true);
    }

    private void setFragment(IssueFragment fragment) {
        mFragment = fragment;
        setChildScrollDelegate(fragment);
    }

    private void updateHeader() {
        TextView tvState = mHeader.findViewById(R.id.tv_state);
        boolean closed = mIssue.state() == IssueState.Closed;
        int stateTextResId = closed ? R.string.closed : R.string.open;
        int stateColorAttributeId = closed ? R.attr.colorIssueClosed : R.attr.colorIssueOpen;

        tvState.setText(getString(stateTextResId).toUpperCase(Locale.getDefault()));
        transitionHeaderToColor(stateColorAttributeId,
                closed ? R.attr.colorIssueClosedDark : R.attr.colorIssueOpenDark);

        TextView tvTitle = mHeader.findViewById(R.id.tv_title);
        tvTitle.setText(mIssue.title());

        mHeader.setVisibility(View.VISIBLE);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.issue_menu, menu);

        boolean authorized = Gh4Application.get().isAuthorized();
        boolean isCreator = mIssue != null && authorized &&
                ApiHelpers.loginEquals(mIssue.user(), Gh4Application.get().getAuthLogin());
        boolean isClosed = mIssue != null && mIssue.state() == IssueState.Closed;
        boolean isCollaborator = mIsCollaborator != null && mIsCollaborator;
        boolean closerIsCreator = mIssue != null
                && ApiHelpers.userEquals(mIssue.user(), mIssue.closedBy());
        boolean canClose = mIssue != null && authorized && (isCreator || isCollaborator);
        boolean canOpen = canClose && (isCollaborator || closerIsCreator);

        if (!canClose || isClosed) {
            menu.removeItem(R.id.issue_close);
        }
        if (!canOpen || !isClosed) {
            menu.removeItem(R.id.issue_reopen);
        }

        if (mIssue == null) {
            menu.removeItem(R.id.browser);
            menu.removeItem(R.id.share);
            menu.removeItem(R.id.copy_number);
        }

        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean displayDetachAction() {
        return true;
    }

    @Override
    protected Intent navigateUp() {
        return IssueListActivity.makeIntent(this, mRepoOwner, mRepoName);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int itemId = item.getItemId();
        switch (itemId) {
            case R.id.issue_close:
                if (checkForAuthOrExit()) {
                    showCloseReasonDialog();
                }
                return true;
            case R.id.issue_reopen:
                if (checkForAuthOrExit()) {
                    showReopenConfirmDialog();
                }
                return true;
            case R.id.share:
                IntentUtils.share(this, getString(R.string.share_issue_subject,
                        mIssueNumber, mIssue.title(), mRepoOwner + "/" + mRepoName),
                        Uri.parse(mIssue.htmlUrl()));
                return true;
            case R.id.browser:
                IntentUtils.launchBrowser(this, Uri.parse(mIssue.htmlUrl()));
                return true;
            case R.id.copy_number:
                IntentUtils.copyToClipboard(this, "Issue #" + mIssueNumber,
                        String.valueOf(mIssueNumber));
                return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onRefresh() {
        mIssue = null;
        mIsCollaborator = null;
        setContentShown(false);

        transitionHeaderToColor(androidx.appcompat.R.attr.colorPrimary, androidx.appcompat.R.attr.colorPrimaryDark);
        mHeader.setVisibility(View.GONE);

        if (mFragment != null) {
            getSupportFragmentManager().beginTransaction()
                    .remove(mFragment)
                    .commit();
            setFragment(null);
        }

        // onRefresh() can be triggered in the draw loop, and CoordinatorLayout doesn't
        // like its child list being changed while drawing
        mHandler.post(this::updateFabVisibility);

        supportInvalidateOptionsMenu();
        loadIssue(true);
        loadCollaboratorStatus(true);
        super.onRefresh();
    }

    @Override
    public void onBackPressed() {
        if (mFragment != null && mFragment.onBackPressed()) {
            return;
        }
        super.onBackPressed();
    }

    @Override
    public void onConfirmed(String tag, Parcelable data) {
        reopenIssue();
    }

    private void showReopenConfirmDialog() {
        ConfirmationDialogFragment.show(this, R.string.reopen_issue_confirm,
                R.string.pull_request_reopen, null, "reopenconfirm");
    }

    private void showCloseReasonDialog() {
        new CloseReasonDialogFragment().show(getSupportFragmentManager(), "close_reason");
    }

    private void reopenIssue() {
        IssueService service = ServiceFactory.get(IssueService.class, false);
        IssueRequest request = IssueRequest.builder()
                .state(IssueState.Open)
                .build();

        String failureMessage = getString(R.string.issue_error_reopen, mIssueNumber);
        service.editIssue(mRepoOwner, mRepoName, mIssueNumber, request)
                .map(ApiHelpers::throwOnFailure)
                .compose(RxUtils.wrapForBackgroundTask(this, R.string.opening_msg, failureMessage))
                .subscribe(this::updateUiAfterStateUpdate, error -> handleActionFailure("Reopening issue failed", error));
    }

    private void closeIssue(IssueStateReason reason) {
        IssueService service = ServiceFactory.get(IssueService.class, false);
        IssueRequest request = IssueRequest.builder()
                .state(IssueState.Closed)
                .stateReason(reason)
                .build();

        String failureMessage = getString(R.string.issue_error_close, mIssueNumber);
        service.editIssue(mRepoOwner, mRepoName, mIssueNumber, request)
                .map(ApiHelpers::throwOnFailure)
                .compose(RxUtils.wrapForBackgroundTask(this, R.string.closing_msg, failureMessage))
                .subscribe(this::updateUiAfterStateUpdate, error -> handleActionFailure("Closing issue failed", error));
    }

    private void updateUiAfterStateUpdate(Issue updatedIssue) {
        mIssue = updatedIssue;

        updateHeader();
        if (mEditFab != null) {
            mEditFab.setState(mIssue.state());
        }
        if (mFragment != null) {
            mFragment.updateState(mIssue);
        }
        setResult(RESULT_OK);
        supportInvalidateOptionsMenu();
    }

    private void updateFabVisibility() {
        boolean isIssueOwner = mIssue != null
                && ApiHelpers.loginEquals(mIssue.user(), Gh4Application.get().getAuthLogin());
        boolean isCollaborator = mIsCollaborator != null && mIsCollaborator;
        boolean shouldHaveFab = (isIssueOwner || isCollaborator) && mIssue != null;
        CoordinatorLayout rootLayout = getRootLayout();

        if (shouldHaveFab && mEditFab == null) {
            mEditFab = (IssueStateTrackingFloatingActionButton)
                    getLayoutInflater().inflate(R.layout.issue_edit_fab, rootLayout, false);
            mEditFab.setOnClickListener(this);
            rootLayout.addView(mEditFab);
        } else if (!shouldHaveFab && mEditFab != null) {
            rootLayout.removeView(mEditFab);
            mEditFab = null;
        }
        if (mEditFab != null) {
            mEditFab.setState(mIssue.state());
        }
    }

    private boolean checkForAuthOrExit() {
        if (Gh4Application.get().isAuthorized()) {
            return true;
        }
        Intent intent = new Intent(this, Github4AndroidActivity.class);
        startActivity(intent);
        finish();
        return false;
    }

    @Override
    public void onClick(View v) {
        if (v.getId() == R.id.edit_fab && checkForAuthOrExit()) {
            Intent editIntent = IssueEditActivity.makeEditIntent(this,
                    mRepoOwner, mRepoName, mIssue);
            mEditIssueLauncher.launch(editIntent);
        }
    }

    private void loadIssue(boolean force) {
        IssueService service = ServiceFactory.get(IssueService.class, force);
        service.getIssue(mRepoOwner, mRepoName, mIssueNumber)
                .map(ApiHelpers::throwOnFailure)
                .compose(makeLoaderSingle(ID_LOADER_ISSUE, force))
                .subscribe(result -> {
                    mIssue = result;
                    showUiIfDone();
                    supportInvalidateOptionsMenu();
                }, this::handleLoadFailure);
    }

    private void loadCollaboratorStatus(boolean force) {
        SingleFactory.isAppUserRepoCollaborator(mRepoOwner, mRepoName, force)
                .compose(makeLoaderSingle(ID_LOADER_COLLABORATOR_STATUS, force))
                .subscribe(result -> {
                    mIsCollaborator = result;
                    showUiIfDone();
                    supportInvalidateOptionsMenu();
                }, this::handleLoadFailure);
    }

    @Nullable
    @Override
    protected Uri getActivityUri() {
        return IntentUtils.createBaseUriForRepo(mRepoOwner, mRepoName)
                .appendPath("issues")
                .appendPath(String.valueOf(mIssueNumber))
                .build();
    }

    public static class CloseReasonDialogFragment extends DialogFragment {
        @NonNull
        @Override
        public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
            var reasonItems = new ArrayList<ItemsWithDescriptionAdapter.Item>();
            reasonItems.add(new ItemsWithDescriptionAdapter.Item(
                            getString(R.string.issue_reason_completed),
                            getString(R.string.issue_reason_completed_description)));
            reasonItems.add(new ItemsWithDescriptionAdapter.Item(
                            getString(R.string.issue_reason_not_planned),
                            getString(R.string.issue_reason_not_planned_description)));

            IssueActivity activity = (IssueActivity) requireActivity();
            return new AlertDialog.Builder(activity)
                    .setTitle(R.string.close_issue_dialog_title)
                    .setAdapter(
                        new ItemsWithDescriptionAdapter(activity, reasonItems),
                        (dialog, itemIndex) -> {
                            var closeReason = itemIndex == 0 ? IssueStateReason.Completed : IssueStateReason.NotPlanned;
                            activity.closeIssue(closeReason);
                        }
                    )
                    .setNegativeButton(R.string.cancel, null)
                    .create();
        }
    }
}
