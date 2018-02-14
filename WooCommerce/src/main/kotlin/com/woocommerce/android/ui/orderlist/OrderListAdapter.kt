package com.woocommerce.android.ui.orderlist

import android.support.v7.widget.RecyclerView
import android.util.Log
import android.view.View
import android.widget.TextView
import com.woocommerce.android.R
import com.woocommerce.android.ui.model.TimeGroup
import com.woocommerce.android.widgets.SectionParameters
import com.woocommerce.android.widgets.SectionedRecyclerViewAdapter
import com.woocommerce.android.widgets.StatelessSection
import org.wordpress.android.fluxc.model.WCOrderModel
import org.wordpress.android.util.DateTimeUtils
import java.util.Currency
import java.util.Date

/**
 * Adapter serves up list of [WCOrderModel] items grouped by the appropriate [TimeGroup].
 */
class OrderListAdapter : SectionedRecyclerViewAdapter() {
    companion object {
        val TAG: String = OrderListAdapter::class.java.simpleName
    }

    fun setOrders(orders: List<WCOrderModel>) {
        // clear all the current data from the adapter
        removeAllSections()

        // Build a list for each [TimeGroup] section
        val listToday = ArrayList<WCOrderModel>()
        val listYesterday = ArrayList<WCOrderModel>()
        val listTwoDays = ArrayList<WCOrderModel>()
        val listWeek = ArrayList<WCOrderModel>()
        val listMonth = ArrayList<WCOrderModel>()

        orders.forEach {
            // Default to today if the date cannot be parsed
            val date: Date = DateTimeUtils.dateFromIso8601(it.dateCreated) ?: Date()
            val timeGroup = TimeGroup.getTimeGroupForDate(date)
            when (timeGroup) {
                TimeGroup.GROUP_TODAY -> listToday.add(it)
                TimeGroup.GROUP_YESTERDAY -> listYesterday.add(it)
                TimeGroup.GROUP_OLDER_TWO_DAYS -> listTwoDays.add(it)
                TimeGroup.GROUP_OLDER_WEEK -> listWeek.add(it)
                TimeGroup.GROUP_OLDER_MONTH -> listMonth.add(it)
            }
        }

        if (listToday.size > 0) {
            addSection(OrderListSection(TimeGroup.GROUP_TODAY.name, listToday))
        }

        if (listYesterday.size > 0) {
            addSection(OrderListSection(TimeGroup.GROUP_YESTERDAY.name, listYesterday))
        }

        if (listTwoDays.size > 0) {
            addSection(OrderListSection(TimeGroup.GROUP_OLDER_TWO_DAYS.name, listTwoDays))
        }

        if (listWeek.size > 0) {
            addSection(OrderListSection(TimeGroup.GROUP_OLDER_WEEK.name, listWeek))
        }

        if (listMonth.size > 0) {
            addSection(OrderListSection(TimeGroup.GROUP_OLDER_MONTH.name, listMonth))
        }
        notifyDataSetChanged()
    }

    /**
     * Custom class represents a single [TimeGroup] and it's assigned list of [WCOrderModel]. Responsible
     * for providing and populating the header and item view holders.
     */
    private class OrderListSection(val title: String, val list: List<WCOrderModel>) : StatelessSection(
            SectionParameters.Builder(R.layout.order_list_item).headerResourceId(R.layout.order_list_header).build()
    ) {
        override fun getContentItemsTotal() = list.size

        override fun getItemViewHolder(view: View): RecyclerView.ViewHolder {
            return ItemViewHolder(view)
        }

        override fun onBindItemViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val order = list[position]
            val itemHolder = holder as ItemViewHolder

            var currencySymbol = ""
            try {
                currencySymbol = Currency.getInstance(order.currency).symbol
            } catch (e: IllegalArgumentException) {
                Log.e(TAG, "Error finding valid currency symbol for currency code [${order.currency}]", e)
            }

            val resources = itemHolder.rootView.context.applicationContext.resources
            itemHolder.orderNum.text = resources.getString(R.string.orderlist_item_order_num, order.remoteOrderId)
            itemHolder.orderName.text = resources.getString(
                    R.string.orderlist_item_order_name, order.billingFirstName, order.billingLastName)
            itemHolder.orderTotal.text = resources.getString(
                    R.string.orderlist_item_order_total, currencySymbol, order.total)
        }

        override fun getHeaderViewHolder(view: View): RecyclerView.ViewHolder {
            return HeaderViewHolder(view)
        }

        override fun onBindHeaderViewHolder(holder: RecyclerView.ViewHolder) {
            val headerViewHolder = holder as HeaderViewHolder

            when (TimeGroup.valueOf(title)) {
                TimeGroup.GROUP_OLDER_MONTH -> headerViewHolder.title.setText(R.string.date_timeframe_older_month)
                TimeGroup.GROUP_OLDER_WEEK -> headerViewHolder.title.setText(R.string.date_timeframe_older_week)
                TimeGroup.GROUP_OLDER_TWO_DAYS -> headerViewHolder.title.setText(R.string.date_timeframe_older_two_days)
                TimeGroup.GROUP_YESTERDAY -> headerViewHolder.title.setText(R.string.date_timeframe_yesterday)
                TimeGroup.GROUP_TODAY -> headerViewHolder.title.setText(R.string.date_timeframe_today)
            }
        }
    }

    private class ItemViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        var orderNum: TextView = view.findViewById(R.id.orderNum)
        var orderName: TextView = view.findViewById(R.id.orderName)
        var orderTotal: TextView = view.findViewById(R.id.orderTotal)
        var rootView = view
    }

    private class HeaderViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val title = view.findViewById<TextView>(R.id.orderListHeader)!!
    }
}