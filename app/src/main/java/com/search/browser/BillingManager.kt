package com.search.browser

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.*

/**
 * Wraps Google Play Billing for the "Buy me a coffee" supporter tiers.
 * Products are consumable (so a supporter can give again), and consuming
 * implicitly acknowledges the purchase. A persistent "supporter" flag is
 * kept in Settings so the badge/thank-you survive the consume.
 */
class BillingManager(
    private val activity: Activity,
    private val onPrices: (Map<String, String>) -> Unit,
    private val onSupporterChanged: (Boolean) -> Unit
) {
    private val productIds = listOf("supporter_coffee", "supporter_snack", "supporter_meal")
    private var details: Map<String, ProductDetails> = emptyMap()

    private val purchasesListener = PurchasesUpdatedListener { result, purchases ->
        if (result.responseCode == BillingClient.BillingResponseCode.OK && purchases != null) {
            purchases.forEach { handlePurchase(it) }
        }
    }

    private val client = BillingClient.newBuilder(activity)
        .setListener(purchasesListener)
        .enablePendingPurchases(
            PendingPurchasesParams.newBuilder().enableOneTimeProducts().build()
        )
        .build()

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var retries = 0
    private var finished = false
    // A tap that arrived before the prices did, carried out once they land.
    private var pendingLaunch: String? = null

    fun start() {
        if (finished) return
        if (client.isReady) {
            queryProducts()
            queryOwned()
            return
        }
        client.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    retries = 0
                    queryProducts()
                    queryOwned()
                }
            }
            /**
             * Reconnect, rather than go quiet.
             *
             * This was a comment reading "reconnect lazily on next action" over
             * an empty body - and the next action, launch(), simply returned
             * when no product details were loaded. So one disconnect left every
             * support button dead for the rest of the session, with no error
             * and nothing on screen to suggest anything was wrong.
             *
             * Backs off and gives up after a few tries: a phone with no Play
             * services should not be reconnecting in a loop for as long as the
             * screen is open.
             */
            override fun onBillingServiceDisconnected() {
                if (finished || retries >= 3) return
                retries++
                handler.postDelayed({ start() }, 2000L * retries)
            }
        })
    }

    private fun queryProducts() {
        val products = productIds.map {
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId(it)
                .setProductType(BillingClient.ProductType.INAPP)
                .build()
        }
        val params = QueryProductDetailsParams.newBuilder().setProductList(products).build()
        client.queryProductDetailsAsync(params) { result, queryResult ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                val map = HashMap<String, ProductDetails>()
                val prices = HashMap<String, String>()
                queryResult.productDetailsList.forEach { pd ->
                    map[pd.productId] = pd
                    pd.oneTimePurchaseOfferDetails?.formattedPrice?.let { prices[pd.productId] = it }
                }
                details = map
                activity.runOnUiThread {
                    onPrices(prices)
                    // A tap that came in while the prices were still loading.
                    val waiting = pendingLaunch
                    pendingLaunch = null
                    if (waiting != null && details.containsKey(waiting)) launch(waiting)
                }
            }
        }
    }

    fun launch(productId: String) {
        val pd = details[productId]
        if (pd == null) {
            // Nothing loaded yet - first tap, or the connection dropped. Hold
            // the choice, reconnect, and carry it out when the details arrive,
            // instead of silently doing nothing.
            pendingLaunch = productId
            start()
            return
        }
        val params = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(
                listOf(
                    BillingFlowParams.ProductDetailsParams.newBuilder()
                        .setProductDetails(pd)
                        .build()
                )
            )
            .build()
        client.launchBillingFlow(activity, params)
    }

    private fun handlePurchase(purchase: Purchase) {
        if (purchase.purchaseState != Purchase.PurchaseState.PURCHASED) return
        // Mark supporter (persist), then consume so they can give again later.
        Settings.setBool(activity, Settings.IS_SUPPORTER, true)
        activity.runOnUiThread { onSupporterChanged(true) }
        val consumeParams = ConsumeParams.newBuilder()
            .setPurchaseToken(purchase.purchaseToken)
            .build()
        client.consumeAsync(consumeParams) { _, _ -> /* consumed (implicitly acknowledged) */ }
    }

    private fun queryOwned() {
        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.INAPP)
            .build()
        client.queryPurchasesAsync(params) { result, purchases ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                purchases.forEach { handlePurchase(it) }
            }
        }
    }

    fun isSupporter(context: Context): Boolean =
        Settings.getBool(context, Settings.IS_SUPPORTER, false)

    fun end() {
        finished = true
        pendingLaunch = null
        handler.removeCallbacksAndMessages(null)
        client.endConnection()
    }
}
