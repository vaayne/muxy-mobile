import Constants, { ExecutionEnvironment } from 'expo-constants';
import { Platform } from 'react-native';

import { PRODUCT_ID } from './productId';

export type IapProduct = { id: string; displayPrice: string };
export type IapPurchase = {
  productId: string;
  purchaseState: 'pending' | 'purchased' | 'unknown';
  purchaseToken: string | null;
  transactionId: string | null;
  isAcknowledgedAndroid: boolean;
};
export type IapError = { message: string };

export type PurchaseListener = (purchase: IapPurchase) => void;
export type ErrorListener = (error: IapError) => void;

export const IAP_AVAILABLE =
  Constants.executionEnvironment !== ExecutionEnvironment.StoreClient;

type IapModule = typeof import('react-native-iap');
type NativePurchase = import('react-native-iap').Purchase;

let modulePromise: Promise<IapModule | null> | null = null;

async function loadModule(): Promise<IapModule | null> {
  if (!IAP_AVAILABLE) return null;
  if (!modulePromise) {
    modulePromise = import('react-native-iap').catch(() => null);
  }
  return modulePromise;
}

function toIapPurchase(p: NativePurchase): IapPurchase {
  const androidState = p.purchaseStateAndroid;
  let state: IapPurchase['purchaseState'] = 'unknown';
  if (Platform.OS === 'android') {
    if (androidState === 1) state = 'purchased';
    else if (androidState === 2) state = 'pending';
  } else {
    state = 'purchased';
  }
  return {
    productId: p.productId,
    purchaseState: state,
    purchaseToken: p.purchaseToken ?? null,
    transactionId: p.transactionId ?? null,
    isAcknowledgedAndroid: Boolean(p.isAcknowledgedAndroid),
  };
}

export async function connect(): Promise<void> {
  const m = await loadModule();
  if (!m) return;
  await m.initConnection();
}

export async function fetchUnlockProduct(): Promise<IapProduct | null> {
  const m = await loadModule();
  if (!m) return null;
  const products = await m.getProducts({ skus: [PRODUCT_ID] });
  const match = products.find((p) => p.productId === PRODUCT_ID);
  if (!match) return null;
  return { id: match.productId, displayPrice: match.localizedPrice };
}

export async function queryUnlockPurchases(): Promise<IapPurchase[]> {
  const m = await loadModule();
  if (!m) return [];
  const purchases = await m.getAvailablePurchases({ onlyIncludeActiveItems: true });
  return purchases.filter((p) => p.productId === PRODUCT_ID).map(toIapPurchase);
}

export async function buyUnlock(): Promise<void> {
  const m = await loadModule();
  if (!m) throw new Error('In-app purchases are not available in this build.');
  if (Platform.OS === 'ios') {
    await m.requestPurchase({ sku: PRODUCT_ID });
    return;
  }
  await m.requestPurchase({ skus: [PRODUCT_ID] });
}

export async function finalizePurchase(purchase: IapPurchase): Promise<void> {
  const m = await loadModule();
  if (!m) return;
  if (Platform.OS === 'android') {
    if (!purchase.purchaseToken) return;
    if (purchase.isAcknowledgedAndroid) return;
  }
  if (Platform.OS === 'ios' && !purchase.transactionId) return;
  const native = {
    productId: purchase.productId,
    transactionId: purchase.transactionId ?? undefined,
    purchaseToken: purchase.purchaseToken ?? undefined,
    purchaseStateAndroid: purchase.purchaseState === 'purchased' ? 1 : undefined,
    isAcknowledgedAndroid: purchase.isAcknowledgedAndroid,
  } as never;
  await m.finishTransaction({ purchase: native, isConsumable: false });
}

export async function subscribePurchases(
  onUpdate: PurchaseListener,
  onError: ErrorListener,
): Promise<() => void> {
  const m = await loadModule();
  if (!m) return () => {};
  const updateSub = m.purchaseUpdatedListener((p) => {
    try {
      onUpdate(toIapPurchase(p));
    } catch {}
  });
  const errorSub = m.purchaseErrorListener((e) => {
    try {
      onError({ message: e.message });
    } catch {}
  });
  return () => {
    updateSub.remove();
    errorSub.remove();
  };
}

export function isPurchased(purchase: IapPurchase): boolean {
  return purchase.productId === PRODUCT_ID && purchase.purchaseState === 'purchased';
}
