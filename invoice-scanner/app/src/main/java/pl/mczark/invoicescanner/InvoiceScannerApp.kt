package pl.mczark.invoicescanner

import android.app.Application
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import pl.mczark.invoicescanner.auth.GmailAuthorizer
import pl.mczark.invoicescanner.data.InvoiceRepository
import pl.mczark.invoicescanner.data.InvoiceScanner
import pl.mczark.invoicescanner.extract.AttachmentTextExtractor

class InvoiceScannerApp : Application() {

    lateinit var repository: InvoiceRepository
        private set
    lateinit var scanner: InvoiceScanner
        private set
    lateinit var authorizer: GmailAuthorizer
        private set

    override fun onCreate() {
        super.onCreate()
        PDFBoxResourceLoader.init(applicationContext)
        repository = InvoiceRepository(this)
        scanner = InvoiceScanner(repository, AttachmentTextExtractor(this))
        authorizer = GmailAuthorizer(this)
    }
}
