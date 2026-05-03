package com.muxy.app.data

import android.app.Activity
import android.content.Context
import android.util.Log
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.muxy.app.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

sealed class Entitlement {
    data object Loading : Entitlement()
    data object Beta : Entitlement()
    data class Trial(val msRemaining: Long) : Entitlement()
    data object Expired : Entitlement()
    data object Unlocked : Entitlement()
}

class BillingRepository(
    context: Context,
    private val scope: CoroutineScope,
    private val trialStore: TrialStore,
    private val now: () -> Long = { System.currentTimeMillis() },
) {
    private val appContext = context.applicationContext

    private val _purchased = MutableStateFlow(false)
    private val _trialStartedAt = MutableStateFlow(trialStore.startedAt())
    private val _productDetails = MutableStateFlow<ProductDetails?>(null)
    val productDetails: StateFlow<ProductDetails?> = _productDetails.asStateFlow()

    val entitlement: StateFlow<Entitlement> = combine(_purchased, _trialStartedAt) { bought, started ->
        compute(bought, started)
    }.stateIn(scope, SharingStarted.Eagerly, Entitlement.Loading)

    private fun compute(bought: Boolean, started: Long?): Entitlement {
        if (!BuildConfig.BILLING_ENABLED) return Entitlement.Unlocked
        if (bought) return Entitlement.Unlocked
        if (!BuildConfig.BILLING_ENFORCED) return Entitlement.Beta
        if (started == null) return Entitlement.Loading
        val remaining = TrialStore.TRIAL_DURATION_MS - (now() - started)
        return if (remaining > 0) Entitlement.Trial(remaining) else Entitlement.Expired
    }

    private val billingClient: BillingClient? = if (BuildConfig.BILLING_ENABLED) {
        BillingClient.newBuilder(appContext)
            .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
            .setListener(PurchasesUpdatedListener { _, purchases -> handlePurchases(purchases.orEmpty()) })
            .build()
    } else null

    init {
        if (BuildConfig.BILLING_ENABLED) startBillingConnection()
    }

    fun startTrialIfAbsent() {
        if (!BuildConfig.BILLING_ENFORCED) return
        val started = trialStore.startIfAbsent(now())
        _trialStartedAt.value = started
    }

    @Volatile private var retryAttempt: Int = 0

    private fun startBillingConnection() {
        val client = billingClient ?: return
        Log.i(TAG, "Connecting to Play Billing...")
        client.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                Log.i(TAG, "onBillingSetupFinished: code=${result.responseCode} msg=${result.debugMessage}")
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    retryAttempt = 0
                    scope.launch {
                        queryPurchases()
                        queryProduct()
                    }
                } else {
                    scheduleReconnect()
                }
            }
            override fun onBillingServiceDisconnected() {
                Log.w(TAG, "Billing service disconnected; scheduling reconnect")
                scheduleReconnect()
            }
        })
    }

    private fun scheduleReconnect() {
        val attempt = retryAttempt.coerceAtMost(5)
        retryAttempt = attempt + 1
        val backoffMs = (1_000L shl attempt).coerceAtMost(30_000L)
        scope.launch {
            delay(backoffMs)
            startBillingConnection()
        }
    }

    private suspend fun queryPurchases() {
        val client = billingClient ?: return
        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.INAPP)
            .build()
        val (result, purchases) = suspendCancellableCoroutine { cont ->
            client.queryPurchasesAsync(params) { r, l -> cont.resume(r to l) }
        }
        Log.i(TAG, "queryPurchasesAsync: code=${result.responseCode} msg=${result.debugMessage} count=${purchases.size}")
        handlePurchases(purchases)
    }

    private suspend fun queryProduct() {
        val client = billingClient ?: return
        val params = QueryProductDetailsParams.newBuilder().setProductList(
            listOf(
                QueryProductDetailsParams.Product.newBuilder()
                    .setProductId(PRODUCT_ID)
                    .setProductType(BillingClient.ProductType.INAPP)
                    .build(),
            ),
        ).build()
        val (result, list) = suspendCancellableCoroutine { cont ->
            client.queryProductDetailsAsync(params) { r, l -> cont.resume(r to l) }
        }
        Log.i(TAG, "queryProductDetailsAsync: code=${result.responseCode} msg=${result.debugMessage} count=${list.size} ids=${list.map { it.productId }}")
        _productDetails.value = list.firstOrNull { it.productId == PRODUCT_ID }
    }

    private fun handlePurchases(purchases: List<Purchase>) {
        val entitled = purchases.any {
            it.products.contains(PRODUCT_ID) && it.purchaseState == Purchase.PurchaseState.PURCHASED
        }
        if (entitled) _purchased.value = true
        purchases.forEach { p ->
            if (p.purchaseState == Purchase.PurchaseState.PURCHASED && !p.isAcknowledged) {
                val ackParams = AcknowledgePurchaseParams.newBuilder()
                    .setPurchaseToken(p.purchaseToken)
                    .build()
                billingClient?.acknowledgePurchase(ackParams) { /* no-op */ }
            }
        }
    }

    fun launchPurchase(activity: Activity) {
        val client = billingClient ?: return
        val details = _productDetails.value ?: return
        val params = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(
                listOf(
                    BillingFlowParams.ProductDetailsParams.newBuilder()
                        .setProductDetails(details)
                        .build(),
                ),
            )
            .build()
        client.launchBillingFlow(activity, params)
    }

    fun restore() {
        scope.launch(Dispatchers.IO) { queryPurchases() }
    }

    companion object {
        const val PRODUCT_ID = "muxy_unlock"
        private const val TAG = "MuxyBilling"
    }
}
