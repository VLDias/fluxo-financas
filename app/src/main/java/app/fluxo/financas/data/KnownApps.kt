package app.fluxo.financas.data

data class BankApp(val pkg: String, val label: String, val accountName: String, val color: Int)

object KnownApps {
    val banks = listOf(
        BankApp("com.nu.production", "Nubank", "Nubank", 0xFF820AD1.toInt()),
        BankApp("com.itau", "Itaú", "Itaú", 0xFFEC7000.toInt()),
        BankApp("com.bradesco", "Bradesco", "Bradesco", 0xFFCC092F.toInt()),
        BankApp("com.santander.app", "Santander", "Santander", 0xFFEC0000.toInt()),
        BankApp("br.com.gabba.Caixa", "Caixa", "Caixa", 0xFF0067AB.toInt()),
        BankApp("com.caixa", "Caixa", "Caixa", 0xFF0067AB.toInt()),
        BankApp("com.intermedium", "Inter", "Inter", 0xFFFF7A00.toInt()),
        BankApp("com.c6bank.app", "C6 Bank", "C6 Bank", 0xFF242424.toInt()),
        BankApp("com.picpay", "PicPay", "PicPay", 0xFF21C25E.toInt()),
        BankApp("com.mercadopago.wallet", "Mercado Pago", "Mercado Pago", 0xFF009EE3.toInt()),
        BankApp("com.neon.app", "Neon", "Neon", 0xFF00AEEF.toInt()),
        BankApp("br.com.bb.android", "Banco do Brasil", "Banco do Brasil", 0xFFFFD400.toInt()),
        BankApp("com.bb.android", "Banco do Brasil", "Banco do Brasil", 0xFFFFD400.toInt()),
        BankApp("com.btg.pactual", "BTG Pactual", "BTG Pactual", 0xFF001E62.toInt()),
        BankApp("com.brbtg.pactual", "BTG Pactual", "BTG Pactual", 0xFF001E62.toInt()),
        BankApp("com.sicoob", "Sicoob", "Sicoob", 0xFF003641.toInt()),
        BankApp("br.com.sicredi", "Sicredi", "Sicredi", 0xFF48A23F.toInt()),
        BankApp("com.original", "Original", "Original", 0xFF00A859.toInt()),
        BankApp("com.wise", "Wise", "Wise", 0xFF9FE870.toInt()),
        BankApp("com.revolut.revolut", "Revolut", "Revolut", 0xFF191C1F.toInt()),
        BankApp("com.google.android.apps.walletnfcrel", "Google Wallet", "Google Wallet", 0xFF4285F4.toInt()),
        BankApp("com.pagseguro", "PagBank", "PagBank", 0xFF00A868.toInt()),
        BankApp("br.com.bb.garcom", "Banco do Brasil", "Banco do Brasil", 0xFFFFD400.toInt())
    )

    private val prefixes = listOf(
        "com.nu.production", "com.itau", "com.bradesco", "com.santander", "com.caixa",
        "br.com.gabba.caixa", "com.intermedium", "com.c6bank", "com.picpay",
        "com.mercadopago", "com.neon", "br.com.bb", "com.bb.android", "com.btg",
        "com.brbtg", "com.sicoob", "br.com.sicredi", "com.original", "com.wise",
        "com.revolut", "com.pagseguro", "com.google.android.apps.walletnfcrel",
        "com.nubank", "com.bancointer", "com.stone", "com.pagarme"
    )

    fun find(pkg: String): BankApp? = banks.firstOrNull { it.pkg == pkg }
        ?: banks.firstOrNull { pkg.startsWith(it.pkg.substringBeforeLast(".")) }

    fun isKnownBank(pkg: String): Boolean =
        prefixes.any { pkg == it || pkg.startsWith("$it.") } || find(pkg) != null

    fun labelFor(pkg: String): String = find(pkg)?.label ?: pkg.substringAfterLast('.').replaceFirstChar { it.uppercase() }
}
