package com.woocommerce.android.ui.orders

import com.woocommerce.android.ui.base.BasePresenter
import com.woocommerce.android.ui.base.BaseView
import org.wordpress.android.fluxc.model.order.OrderIdentifier

interface AddOrderNoteContract {
    interface Presenter : BasePresenter<View> {
        fun hasBillingEmail(orderId: OrderIdentifier): Boolean
    }

    interface View : BaseView<Presenter> {
        fun getNoteText(): String
        fun confirmDiscard()
    }
}
