package pl.mczark.invoicescanner.payment

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.math.BigDecimal

class TransferQrPayloadTest {

    @Test
    fun `builds ZBP payload`() {
        val payload = TransferQrPayload.build(
            iban = "PL61109010140000071219812874",
            amount = BigDecimal("123.45"),
            currency = "PLN",
            recipient = "ACME Usługi Sp. z o.o. | Oddział",
            title = "Faktura FV/2024/03/0157",
            nip = "526-104-08-28",
        )

        assertEquals(
            "5261040828|PL|61109010140000071219812874|012345|ACME Usługi Sp. z o.|Faktura FV/2024/03/0157|||",
            payload,
        )
    }

    @Test
    fun `rejects foreign currency and amounts above the format limit`() {
        assertNull(TransferQrPayload.build("PL61109010140000071219812874", BigDecimal("10"), "EUR", "A", "B"))
        assertNull(TransferQrPayload.build("PL61109010140000071219812874", BigDecimal("10000.00"), "PLN", "A", "B"))
        assertNull(TransferQrPayload.build("DE89370400440532013000", BigDecimal("10"), "PLN", "A", "B"))
    }
}
