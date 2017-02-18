package me.saket.dank.ui.submission;

import static butterknife.ButterKnife.findById;
import static me.saket.dank.utils.RxUtils.applySchedulers;
import static me.saket.dank.utils.RxUtils.logError;
import static rx.Observable.just;

import android.app.Fragment;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.support.annotation.Nullable;
import android.support.v4.content.ContextCompat;
import android.support.v7.widget.DefaultItemAnimator;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.support.v7.widget.Toolbar;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ImageView;
import android.widget.TextView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.Priority;
import com.bumptech.glide.load.resource.drawable.GlideDrawable;
import com.bumptech.glide.request.target.GlideDrawableImageViewTarget;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import net.dean.jraw.models.CommentNode;
import net.dean.jraw.models.Submission;
import net.dean.jraw.models.Submission.PostHint;

import java.io.IOException;
import java.util.LinkedList;
import java.util.List;

import butterknife.BindDrawable;
import butterknife.BindView;
import butterknife.ButterKnife;
import me.saket.dank.BuildConfig;
import me.saket.dank.R;
import me.saket.dank.di.Dank;
import me.saket.dank.ui.subreddits.SubRedditActivity;
import me.saket.dank.utils.DeviceUtils;
import me.saket.dank.utils.Views;
import me.saket.dank.widgets.AnimatableProgressBar;
import me.saket.dank.widgets.AnimatableToolbarBackground;
import me.saket.dank.widgets.InboxUI.ExpandablePageLayout;
import me.saket.dank.widgets.ScrollingRecyclerViewSheet;
import rx.Subscription;
import timber.log.Timber;

public class SubmissionFragment extends Fragment implements ExpandablePageLayout.Callbacks, ExpandablePageLayout.OnPullToCollapseIntercepter {

    private static final String KEY_SUBMISSION_JSON = "submissionJson";
    private static final java.lang.String BLANK_PAGE_URL = "about:blank";

    @BindView(R.id.toolbar) Toolbar toolbar;
    @BindView(R.id.submission_toolbar_shadow) View toolbarShadows;
    @BindView(R.id.submission_toolbar_background) AnimatableToolbarBackground toolbarBackground;
    @BindView(R.id.submission_content_progress) AnimatableProgressBar contentLoadProgressView;
    @BindView(R.id.submission_webview_container) ViewGroup contentWebViewContainer;
    @BindView(R.id.submission_webview) WebView contentWebView;
    @BindView(R.id.submission_linked_image) ImageView contentImageView;
    @BindView(R.id.submission_comment_list_parent_sheet) ScrollingRecyclerViewSheet commentListParentSheet;
    @BindView(R.id.submission_comments_header) ViewGroup commentsHeaderView;
    @BindView(R.id.submission_title) TextView titleView;
    @BindView(R.id.submission_subtitle) TextView subtitleView;
    @BindView(R.id.submission_selfpost_text) TextView selfPostTextView;
    @BindView(R.id.submission_comment_list) RecyclerView commentList;
    @BindView(R.id.submission_comments_progress) View commentsLoadProgressView;

    @BindDrawable(R.drawable.ic_close_black_24dp) Drawable closeIconDrawable;

    private ExpandablePageLayout submissionPageLayout;
    private CommentsAdapter commentsAdapter;
    private Subscription commentsSubscription;
    private CommentsCollapseHelper commentsCollapseHelper;
    private Submission currentSubmission;
    private List<Runnable> pendingOnExpandRunnables = new LinkedList<>();

    public interface Callbacks {
        void onSubmissionToolbarUpClick();
    }

    public static SubmissionFragment create() {
        return new SubmissionFragment();
    }

    @Nullable
    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View fragmentLayout = inflater.inflate(R.layout.fragment_submission, container, false);
        ButterKnife.bind(this, fragmentLayout);

        submissionPageLayout = findById(getActivity(), R.id.subreddit_submission_page);
        submissionPageLayout.addCallbacks(this);
        submissionPageLayout.setPullToCollapseIntercepter(this);

        fragmentLayout.setOnApplyWindowInsetsListener((v, insets) -> {
            int statusBarHeight = insets.getSystemWindowInsetTop();
            Views.setMarginTop(toolbar, statusBarHeight);
            Views.executeOnMeasure(toolbar, () -> {
                Views.setHeight(toolbarBackground, toolbar.getHeight() + statusBarHeight);
            });
            return insets;
        });

        // Add a close icon to the toolbar.
        closeIconDrawable = closeIconDrawable.mutate();
        closeIconDrawable.setTint(ContextCompat.getColor(getActivity(), R.color.white));
        toolbar.setNavigationIcon(closeIconDrawable);
        toolbar.setNavigationOnClickListener(v -> ((Callbacks) getActivity()).onSubmissionToolbarUpClick());
        toolbar.setBackground(null);
        toolbarBackground.syncBottomWithViewTop(commentListParentSheet);

        // TODO: 01/02/17 Should we preload Views for adapter rows?
        // Setup comment list and its adapter.
        commentsAdapter = new CommentsAdapter();
        commentsAdapter.setOnCommentClickListener(comment -> {
            // Collapse/expand on tap.
            commentsAdapter.updateData(commentsCollapseHelper.toggleCollapseAndGet(comment));
        });
        commentList.setAdapter(RecyclerAdapterWithHeader.wrap(commentsAdapter, commentsHeaderView));
        commentList.setLayoutManager(new LinearLayoutManager(getActivity()));
        commentList.setItemAnimator(new DefaultItemAnimator());

        commentsCollapseHelper = new CommentsCollapseHelper();

        setupContentWebView();

        // Restore submission if the Activity was recreated.
        if (savedInstanceState != null) {
            onRestoreSavedInstanceState(savedInstanceState);
        }
        return fragmentLayout;
    }

    private void setupContentWebView() {
        contentWebView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                contentLoadProgressView.setIndeterminate(false);
                contentLoadProgressView.setProgressWithAnimation(newProgress);
                contentLoadProgressView.setVisibility(newProgress < 100 ? View.VISIBLE : View.GONE);
            }
        });
        contentWebView.setWebViewClient(new WebViewClient());
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        if (currentSubmission != null) {
            JsonNode dataNode = currentSubmission.getDataNode();
            outState.putString(KEY_SUBMISSION_JSON, dataNode.toString());
        }
        super.onSaveInstanceState(outState);
    }

    private void onRestoreSavedInstanceState(Bundle savedInstanceState) {
        if (savedInstanceState.containsKey(KEY_SUBMISSION_JSON)) {
            try {
                String submissionJson = savedInstanceState.getString(KEY_SUBMISSION_JSON);
                JsonNode jsonNode = new ObjectMapper().readTree(submissionJson);
                populateUi(new Submission(jsonNode));

            } catch (IOException e) {
                Timber.e(e, "Couldn't deserialize Submission for state restoration");
            }
        }
    }

    /**
     * Update the submission to be shown. Since this Fragment is retained by {@link SubRedditActivity},
     * we only update the UI everytime a new submission is to be shown.
     */
    public void populateUi(Submission submission) {
        currentSubmission = submission;

        // Reset everything.
        contentLoadProgressView.setProgress(0);
        commentListParentSheet.scrollTo(0, 0);
        commentListParentSheet.setScrollingEnabled(false);
        commentsCollapseHelper.reset();
        commentsAdapter.updateData(null);

        boolean isImageOrVideoContent = submission.getPostHint() == PostHint.IMAGE || submission.getPostHint() == PostHint.VIDEO;
        toolbarBackground.setEnabled(isImageOrVideoContent);
        toolbarShadows.setVisibility(isImageOrVideoContent ? View.VISIBLE : View.GONE);

        // Update submission information.
        titleView.setText(submission.getTitle());
        subtitleView.setText(getString(R.string.subreddit_name_r_prefix, submission.getSubredditName()));

        // Load self-text/media/webpage.
        loadSubmissionContent(submission);

        // Load new comments.
        commentsLoadProgressView.setVisibility(View.VISIBLE);
        commentsSubscription = Dank.reddit()
                .authenticateIfNeeded()
                .flatMap(__ -> just(Dank.reddit().fullSubmissionData(submission.getId())))
                .retryWhen(Dank.reddit().refreshApiTokenAndRetryIfExpired())
                .map(submissionData -> {
                    CommentNode commentNode = submissionData.getComments();
                    commentsCollapseHelper.setupWith(commentNode);
                    return commentsCollapseHelper.flattenExpandedComments();
                })
                .compose(applySchedulers())
                .doOnTerminate(() -> commentsLoadProgressView.setVisibility(View.GONE))
                .subscribe(commentsAdapter, logError("Couldn't get comments"));
    }

    private void loadSubmissionContent(Submission submission) {
        Timber.d("-------------------------------------------");
        Timber.i("%s", submission.getTitle());
        Timber.i("Post hint: %s", submission.getPostHint());
        //Timber.i("%s", submission.getDataNode().toString());

        switch (submission.getPostHint()) {
            case IMAGE:
                contentLoadProgressView.setIndeterminate(true);
                contentLoadProgressView.setVisibility(View.VISIBLE);

                // TODO: 18/02/17 Load a low-quality image first, before loading the HD image.
                Glide.with(this)
                        .load(submission.getUrl())
                        .priority(Priority.IMMEDIATE)
                        .into(new GlideDrawableImageViewTarget(contentImageView) {
                            @Override
                            protected void setResource(GlideDrawable resource) {
                                super.setResource(resource);
                                // TransitionDrawable (used by Glide for fading-in) messes up the scaleType. Set it everytime.
                                contentImageView.setScaleType(ImageView.ScaleType.FIT_START);
                                contentLoadProgressView.setVisibility(View.GONE);

                                // TODO: 18/02/17 Calculate distanceToReveal according to the Drawable.
                                int revealDistance = commentListParentSheet.getHeight() * 4 / 10;

                                if (submissionPageLayout.isExpanded()) {
                                    // Smoothly reveal the image.
                                    contentImageView.post(() -> {
                                        commentListParentSheet.setScrollingEnabled(true);
                                        commentListParentSheet.smoothScrollTo(revealDistance);
                                    });

                                } else {
                                    // User has possibly not seen anything yet. Reveal the image right away.
                                    commentListParentSheet.setScrollingEnabled(true);
                                    commentListParentSheet.scrollTo(0, revealDistance);
                                }
                            }
                        });
                break;

            case SELF:
                selfPostTextView.setText(submission.getSelftext());
                break;

            case LINK:
                contentWebView.loadUrl(BLANK_PAGE_URL);
                contentWebView.loadUrl(submission.getUrl());
                commentListParentSheet.setScrollingEnabled(true);
                break;

            case VIDEO:
            case UNKNOWN:
                // TODO: 12/02/17.
                break;

            default:
                throw new UnsupportedOperationException("Unknown post hint: " + submission.getPostHint());
        }

        selfPostTextView.setVisibility(submission.getPostHint() == PostHint.SELF ? View.VISIBLE : View.GONE);
        contentImageView.setVisibility(submission.getPostHint() == PostHint.IMAGE ? View.VISIBLE : View.GONE);
        // WebView is made visible in onPageExpanded() because it's very heavy and can interfere with this page's open animation

        if (submission.getPostHint() == PostHint.LINK) {
            // Show the WebView only when this page is fully expanded or else it'll interfere with the entry animation.
            // Also ignore loading the WebView on the emulator. It is very expensive and greatly slow down the emulator.
            if (!BuildConfig.DEBUG || !DeviceUtils.isEmulator()) {
                pendingOnExpandRunnables.add(() -> {
                    contentWebView.setVisibility(View.VISIBLE);
                });
            }
            contentWebViewContainer.setVisibility(View.VISIBLE);

        } else {
            contentWebView.setVisibility(View.GONE);
            contentWebViewContainer.setVisibility(View.GONE);
        }
    }

// ======== EXPANDABLE PAGE CALLBACKS ======== //

    /**
     * @param upwardPagePull True if the PAGE is being pulled upwards. Remember that upward pull == downward scroll and vice versa.
     * @return True to consume this touch event. False otherwise.
     */
    @Override
    public boolean onInterceptPullToCollapseGesture(MotionEvent event, float downX, float downY, boolean upwardPagePull) {
        Rect commentSheetBounds = new Rect();
        commentListParentSheet.getGlobalVisibleRect(commentSheetBounds);
        boolean touchInsideCommentsSheet = commentSheetBounds.contains((int) downX, (int) downY);

        //noinspection SimplifiableIfStatement
        if (touchInsideCommentsSheet) {
            return upwardPagePull
                    ? commentListParentSheet.canScrollUpwardsAnyFurther()
                    : commentListParentSheet.canScrollDownwardsAnyFurther();
        } else {
            return false;
        }
    }

    @Override
    public void onPageAboutToExpand(long expandAnimDuration) {
    }

    @Override
    public void onPageExpanded() {
        for (Runnable runnable : pendingOnExpandRunnables) {
            runnable.run();
            pendingOnExpandRunnables.remove(runnable);
        }
    }

    @Override
    public void onPageAboutToCollapse(long collapseAnimDuration) {

    }

    @Override
    public void onPageCollapsed() {
        if (commentsSubscription != null) {
            commentsSubscription.unsubscribe();
        }
    }

// ======== BACK-PRESS ======== //

    /**
     * @return true if the back press should be intercepted. False otherwise.
     */
    public boolean handleBackPress() {
        if (currentSubmission != null && !commentListParentSheet.isExpanded() && contentWebView.getVisibility() == View.VISIBLE) {
            if (contentWebView.canGoBack() && !BLANK_PAGE_URL.equals(Views.previousUrlInHistory(contentWebView))) {
                // WebView is visible and can go back.
                contentWebView.goBack();
                return true;
            }
        }

        return false;
    }

}