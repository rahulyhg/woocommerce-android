package com.woocommerce.android.ui.notifications

import android.content.Context
import android.os.Bundle
import android.os.Parcelable
import android.support.design.widget.Snackbar
import android.support.v4.content.ContextCompat
import android.support.v7.widget.DefaultItemAnimator
import android.support.v7.widget.DividerItemDecoration
import android.support.v7.widget.LinearLayoutManager
import android.support.v7.widget.RecyclerView
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import com.woocommerce.android.R
import com.woocommerce.android.analytics.AnalyticsTracker
import com.woocommerce.android.analytics.AnalyticsTracker.Stat
import com.woocommerce.android.extensions.WooNotificationType.NEW_ORDER
import com.woocommerce.android.extensions.WooNotificationType.PRODUCT_REVIEW
import com.woocommerce.android.extensions.WooNotificationType.UNKNOWN
import com.woocommerce.android.extensions.getRemoteOrderId
import com.woocommerce.android.extensions.getWooType
import com.woocommerce.android.extensions.onScrollDown
import com.woocommerce.android.extensions.onScrollUp
import com.woocommerce.android.push.NotificationHandler
import com.woocommerce.android.tools.NetworkStatus
import com.woocommerce.android.tools.SelectedSite
import com.woocommerce.android.ui.base.TopLevelFragment
import com.woocommerce.android.ui.base.UIMessageResolver
import com.woocommerce.android.ui.notifications.NotifsListAdapter.ItemType
import com.woocommerce.android.ui.notifications.NotifsListAdapter.NotifsListItemDecoration
import com.woocommerce.android.ui.orders.OrderListFragment
import com.woocommerce.android.util.WooLog
import com.woocommerce.android.util.WooLog.T.NOTIFICATIONS
import com.woocommerce.android.widgets.AppRatingDialog
import com.woocommerce.android.widgets.SkeletonView
import com.woocommerce.android.widgets.sectionedrecyclerview.SectionedRecyclerViewAdapter.Companion.INVALID_POSITION
import dagger.android.support.AndroidSupportInjection
import kotlinx.android.synthetic.main.fragment_notifs_list.*
import kotlinx.android.synthetic.main.fragment_notifs_list.view.*
import org.wordpress.android.fluxc.model.CommentModel
import org.wordpress.android.fluxc.model.CommentStatus
import org.wordpress.android.fluxc.model.CommentStatus.SPAM
import org.wordpress.android.fluxc.model.CommentStatus.TRASH
import org.wordpress.android.fluxc.model.notification.NotificationModel
import javax.inject.Inject

class NotifsListFragment : TopLevelFragment(),
        NotifsListContract.View,
        NotifsListAdapter.ReviewListListener,
        NotifsListAdapter.ItemDecorationListener {
    companion object {
        val TAG: String = NotifsListFragment::class.java.simpleName
        const val STATE_KEY_LIST = "list-state"
        const val STATE_KEY_REFRESH_PENDING = "is-refresh-pending"

        fun newInstance() = NotifsListFragment()
    }

    @Inject lateinit var presenter: NotifsListContract.Presenter
    @Inject lateinit var notifsAdapter: NotifsListAdapter
    @Inject lateinit var uiMessageResolver: UIMessageResolver
    @Inject lateinit var selectedSite: SelectedSite
    @Inject lateinit var networkStatus: NetworkStatus

    private var changeCommentStatusSnackbar: Snackbar? = null

    // Holds a reference to the index and notification object pending moderation
    private var pendingModerationNewStatus: String? = null
    private var pendingModerationRemoteNoteId: Long? = null

    override var isActive: Boolean = false
        get() = childFragmentManager.backStackEntryCount == 0 && !isHidden

    override var isRefreshPending = true
    private var listState: Parcelable? = null // Save the state of the recycler view

    private val skeletonView = SkeletonView()
    private var menuMarkAllRead: MenuItem? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setHasOptionsMenu(true)
        savedInstanceState?.let { bundle ->
            listState = bundle.getParcelable(OrderListFragment.STATE_KEY_LIST)
            isRefreshPending = bundle.getBoolean(OrderListFragment.STATE_KEY_REFRESH_PENDING, false)
        }
    }

    override fun onCreateOptionsMenu(menu: Menu?, inflater: MenuInflater?) {
        inflater?.inflate(R.menu.menu_notifs_list_fragment, menu)
        menuMarkAllRead = menu?.findItem(R.id.menu_mark_all_read)

        super.onCreateOptionsMenu(menu, inflater)
    }

    override fun onAttach(context: Context?) {
        AndroidSupportInjection.inject(this)
        super.onAttach(context)
    }

    override fun onCreateFragmentView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_notifs_list, container, false)
        with(view) {
            notifsRefreshLayout?.apply {
                activity?.let { activity ->
                    setColorSchemeColors(
                            ContextCompat.getColor(activity, R.color.colorPrimary),
                            ContextCompat.getColor(activity, R.color.colorAccent),
                            ContextCompat.getColor(activity, R.color.colorPrimaryDark)
                    )
                }
                // Set the scrolling view in the custom SwipeRefreshLayout
                scrollUpChild = notifsList
                setOnRefreshListener {
                    AnalyticsTracker.track(Stat.NOTIFICATIONS_LIST_PULLED_TO_REFRESH)

                    notifsRefreshLayout.isRefreshing = false

                    if (!isRefreshPending) {
                        isRefreshPending = true
                        presenter.loadNotifs(forceRefresh = true)
                    }
                }
            }
        }
        return view
    }

    override fun onResume() {
        super.onResume()
        AnalyticsTracker.trackViewShown(this)
        updateMarkAllReadMenuItem()
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)

        notifsAdapter.setListListener(this)

        val unreadDecoration = NotifsListItemDecoration(activity as Context)
        unreadDecoration.setListener(this)

        notifsList.apply {
            layoutManager = LinearLayoutManager(context)
            itemAnimator = DefaultItemAnimator()
            setHasFixedSize(false)
            // divider decoration between items
            addItemDecoration(DividerItemDecoration(context, DividerItemDecoration.VERTICAL))
            // unread item decoration
            addItemDecoration(unreadDecoration)
            adapter = notifsAdapter
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    if (dy > 0) onScrollDown() else if (dy < 0) onScrollUp()
                }
            })
        }

        presenter.takeView(this)

        empty_view.setSiteToShare(selectedSite.get(), Stat.NOTIFICATIONS_SHARE_YOUR_STORE_BUTTON_TAPPED)

        if (isActive && !deferInit) {
            presenter.loadNotifs(forceRefresh = this.isRefreshPending)
        }

        listState?.let {
            notifsList.layoutManager?.onRestoreInstanceState(listState)
            listState = null
        }
    }

    override fun onOptionsItemSelected(item: MenuItem?): Boolean {
        return when (item?.itemId) {
            R.id.menu_mark_all_read -> {
                AnalyticsTracker.track(Stat.NOTIFICATIONS_LIST_MENU_MARK_READ_BUTTON_TAPPED)

                presenter.markAllNotifsRead()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onPrepareOptionsMenu(menu: Menu?) {
        updateMarkAllReadMenuItem()
        super.onPrepareOptionsMenu(menu)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        val listState = notifsList.layoutManager?.onSaveInstanceState()

        outState.putParcelable(STATE_KEY_LIST, listState)
        outState.putBoolean(STATE_KEY_REFRESH_PENDING, isRefreshPending)
        super.onSaveInstanceState(outState)
    }

    override fun onBackStackChanged() {
        super.onBackStackChanged()

        if (isActive) {
            // If this fragment is now visible and we've deferred loading orders due to it not
            // being visible - go ahead and load the orders.
            presenter.loadNotifs(forceRefresh = this.isRefreshPending)
        } else {
            // If this fragment is no longer visible, dismiss the pending notification
            // moderation so it can be processed immediately.
            changeCommentStatusSnackbar?.dismiss()
        }
        updateMarkAllReadMenuItem()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)

        // If this fragment is no longer visible, dismiss the pending notification
        // moderation so it can be processed immediately.
        changeCommentStatusSnackbar?.dismiss()
    }

    override fun onStop() {
        super.onStop()

        changeCommentStatusSnackbar?.dismiss()
    }

    override fun onDestroyView() {
        presenter.dropView()
        super.onDestroyView()
    }

    override fun showNotifications(notifsList: List<NotificationModel>, isFreshData: Boolean) {
        if (!notifsAdapter.isSameList(notifsList)) {
            notifsAdapter.setNotifications(notifsList)
        }
        if (isFreshData) {
            isRefreshPending = false
        }
    }

    override fun getFragmentTitle() = getString(R.string.notifications)

    override fun refreshFragmentState() {
        isRefreshPending = true
        if (isActive) {
            presenter.loadNotifs(forceRefresh = true)
        }
    }

    override fun showSkeleton(show: Boolean) {
        when (show) {
            true -> skeletonView.show(notifsView, R.layout.skeleton_notif_list, delayed = true)
            false -> skeletonView.hide()
        }
    }

    override fun onNotificationClicked(notification: NotificationModel) {
        when (notification.getWooType()) {
            PRODUCT_REVIEW -> {
                if (!notifsRefreshLayout.isRefreshing) {
                    openReviewDetail(notification)
                }
            }
            NEW_ORDER -> {
                notification.getRemoteOrderId()?.let {
                    AnalyticsTracker.track(Stat.NOTIFICATION_OPEN, mapOf(
                            AnalyticsTracker.KEY_TYPE to AnalyticsTracker.VALUE_ORDER,
                            AnalyticsTracker.KEY_ALREADY_READ to notification.read))

                    openOrderDetail(selectedSite.get().id, it, notification.remoteNoteId)
                } ?: WooLog.e(NOTIFICATIONS, "New order notification is missing the order id!").also {
                    showLoadNotificationDetailError()
                }
            }
            UNKNOWN -> {
                WooLog.e(NOTIFICATIONS, "Unknown notification type!")
                showLoadNotificationDetailError()
            }
        }

        AppRatingDialog.incrementInteractions()
    }

    override fun openReviewDetail(notification: NotificationModel) {
        AnalyticsTracker.track(Stat.NOTIFICATION_OPEN, mapOf(
                AnalyticsTracker.KEY_TYPE to AnalyticsTracker.VALUE_REVIEW,
                AnalyticsTracker.KEY_ALREADY_READ to notification.read))

        // If the notification is pending moderation, override the status to display in
        // the detail view.
        val isPendingModeration = pendingModerationRemoteNoteId?.let { it == notification.remoteNoteId } ?: false

        val tag = ReviewDetailFragment.TAG
        getFragmentFromBackStack(tag)?.let { frag ->
            val args = frag.arguments ?: Bundle()

            args.putLong(ReviewDetailFragment.FIELD_REMOTE_NOTIF_ID, notification.remoteNoteId)

            // Reset any existing comment status overrides
            args.remove(ReviewDetailFragment.FIELD_COMMENT_STATUS_OVERRIDE)

            // Add comment status override if needed
            if (isPendingModeration) {
                pendingModerationNewStatus?.let {
                    args.putString(ReviewDetailFragment.FIELD_COMMENT_STATUS_OVERRIDE, it)
                }
            }
            frag.arguments = args
            popToState(tag)
        } ?: if (isPendingModeration) {
            loadChildFragment(ReviewDetailFragment.newInstance(notification, pendingModerationNewStatus), tag)
        } else {
            loadChildFragment(ReviewDetailFragment.newInstance(notification), tag)
        }
    }

    override fun scrollToTop() {
        notifsList?.smoothScrollToPosition(0)
    }

    override fun showLoadNotificationsError() {
        uiMessageResolver.getSnack(R.string.notifs_fetch_error).show()
    }

    override fun showLoadNotificationDetailError() {
        uiMessageResolver.showSnack(R.string.notifs_detail_loading_error)
    }

    override fun notificationModerationError() {
        uiMessageResolver.showSnack(R.string.wc_moderate_review_error)

        revertPendingModeratedNotifState()
    }

    override fun notificationModerationSuccess() {
        resetPendingModerationVariables()
    }

    override fun moderateComment(remoteNoteId: Long, comment: CommentModel, newStatus: CommentStatus) {
        if (networkStatus.isConnected()) {
            pendingModerationNewStatus = newStatus.toString()
            pendingModerationRemoteNoteId = remoteNoteId

            var changeCommentStatusCanceled = false

            AnalyticsTracker.track(Stat.REVIEW_ACTION, mapOf(AnalyticsTracker.KEY_TYPE to newStatus.toString()))

            // Listener for the UNDO button in the snackbar
            val actionListener = View.OnClickListener {
                AnalyticsTracker.track(Stat.SNACK_REVIEW_ACTION_APPLIED_UNDO_BUTTON_TAPPED)

                // User canceled the action to change the order status
                changeCommentStatusCanceled = true

                // Add the notification back to the list
                revertPendingModeratedNotifState()
            }

            val callback = object : Snackbar.Callback() {
                override fun onDismissed(transientBottomBar: Snackbar?, event: Int) {
                    super.onDismissed(transientBottomBar, event)

                    if (!changeCommentStatusCanceled) {
                        comment.status = newStatus.toString()
                        presenter.pushUpdatedComment(comment)
                    }
                }
            }

            changeCommentStatusSnackbar = uiMessageResolver
                    .getUndoSnack(
                            R.string.notifs_review_moderation_undo,
                            newStatus.toString(),
                            actionListener = actionListener
                    ).also {
                        it.addCallback(callback)
                        it.show()
                    }

            // Manually remove the notification from the list if it's new
            // status will be spam or trash
            if (newStatus == SPAM || newStatus == TRASH) {
                removeModeratedNotifFromList(remoteNoteId)
            }

            AppRatingDialog.incrementInteractions()
        } else {
            uiMessageResolver.showOfflineSnack()
        }
    }

    /**
     * Update the UI to visually mark all notifications as read while the request to
     * officially mark notifications as read is being processed.
     */
    override fun visuallyMarkNotificationsAsRead() {
        // Remove all active notifications from the system bar
        context?.let { NotificationHandler.removeAllNotificationsFromSystemBar(it) }

        notifsAdapter.markAllNotifsAsRead()
    }

    override fun showMarkAllNotificationsReadSuccess() {
        uiMessageResolver.showSnack(R.string.wc_mark_all_read_success)
    }

    override fun showMarkAllNotificationsReadError() {
        uiMessageResolver.showSnack(R.string.wc_mark_all_read_error)
    }

    override fun showEmptyView(show: Boolean) {
        if (show) empty_view.show(R.string.notifs_empty_message) else empty_view.hide()
    }
    /**
     * Only show the "mark all read" menu item when this fragment is active and there are unread notifs
     */
    override fun updateMarkAllReadMenuItem() {
        val showMarkAllRead = isActive && presenter.hasUnreadNotifs()
        menuMarkAllRead?.let { if (it.isVisible != showMarkAllRead) it.isVisible = showMarkAllRead }
    }

    private fun revertPendingModeratedNotifState() {
        AnalyticsTracker.track(Stat.REVIEW_ACTION_UNDO)

        val itemPosition = notifsAdapter.revertHiddenNotificationAndReturnPos()
        if (itemPosition != INVALID_POSITION) {
            notifsList.smoothScrollToPosition(itemPosition)
        }
        resetPendingModerationVariables()
    }

    private fun removeModeratedNotifFromList(remoteNoteId: Long) {
        notifsAdapter.hideNotificationWithId(remoteNoteId)
    }

    private fun resetPendingModerationVariables() {
        pendingModerationNewStatus = null
        pendingModerationRemoteNoteId = null
        notifsAdapter.resetPendingModerationState()
    }

    /**
     * Determines whether to show the unread indicator item decoration for the passed position
     */
    override fun getItemTypeAtPosition(position: Int): ItemType {
        return notifsAdapter.getItemTypeAtRecyclerPosition(position)
    }
}
