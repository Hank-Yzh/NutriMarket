package com.nutrimarket.nutrimarket.integration.procurement;

import com.nutrimarket.nutrimarket.model.procurement.ProcurementModels.*;

/** Submits one store's part of an order. Implementations must treat (orderId, store, attempt) as the idempotency key. */
public interface RetailerClient {
    RetailerResponse submit(RetailerRequest request);
    void cancel(String store,String reference);
}
