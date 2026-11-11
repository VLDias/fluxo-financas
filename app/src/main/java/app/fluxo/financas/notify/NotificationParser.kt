package app.fluxo.financas.notify

import java.text.Normalizer
import java.util.Locale
import kotlin.math.min

data class ParsedTxn(
    val amountCents: Long,
    val isExpense: Boolean,
    val directionKnown: Boolean,
    val merchant: String,
    val category: String,
    val method: String?,
    val confidence: Float
)

/**
 * Interpreta notificações de bancos brasileiros. Heurística em camadas:
 * valor em R$ -> sentido (compra/pix enviado x recebido/estorno) -> estabelecimento -> categoria.
 * Quando a confiança é baixa, o app guarda a notificação na fila de revisão em vez de chutar.
 */
object NotificationParser {

    private val IGNORE = listOf(
        "fatura fechada", "fatura aberta", "sua fatura chegou", "fatura disponivel",
        "limite disponivel", "limite de credito", "limite do cartao",
        "codigo de verificacao", "seu codigo de", "token de acesso", "senha",
        "oferta", "promocao", "cupom", "convite", "atualize", "atualizacao",
        "novidade", "parabens", "pre-aprovado", "simule", "ganhe", "indique",
        "programa de pontos", "resgate seus pontos", "vence em", "vencimento da fatura",
        "fatura fechou", "fatura vence", "sua fatura de"
    )

    private val EXPENSE = listOf(
        "compra aprovada", "compra no debito", "compra no credito", "compra de", "compra em",
        "voce comprou", "comprou", "pix enviado", "pix para", "enviou um pix", "voce enviou",
        "transferencia enviada", "ted enviada", "doc enviado",
        "pagamento de", "pagamento aprovado", "pagamento realizado", "pagamento efetuado",
        "voce pagou", "pagou", "saque", "debito de", "tarifa", "cobranca", "boleto",
        "recarga", "mensalidade", "fatura paga", "pagamento da fatura", "debitado",
        "fez um pix", "pix de", "enviou r$", "pagamento recebido"
    )

    private val INCOME = listOf(
        "pix recebido", "recebeu um pix", "voce recebeu", "recebeu", "transferencia recebida",
        "ted recebida", "doc recebido", "deposito", "depositaram", "salario",
        "pagamento recebido", "estorno", "cashback", "reembolso", "rendimento",
        "creditado", "entrada de", "valor recebido", "resgatou"
    )

    private val KNOWN_MERCHANTS = listOf(
        Triple("ifood", "iFood", "Alimentação"),
        Triple("rappi", "Rappi", "Alimentação"),
        Triple("mcdonald", "McDonald's", "Alimentação"),
        Triple("burger king", "Burger King", "Alimentação"),
        Triple("starbucks", "Starbucks", "Alimentação"),
        Triple("padaria", "Padaria", "Alimentação"),
        Triple("restaurante", "Restaurante", "Alimentação"),
        Triple("pizzaria", "Pizzaria", "Alimentação"),
        Triple("lanchonete", "Lanchonete", "Alimentação"),
        Triple("carrefour", "Carrefour", "Mercado"),
        Triple("pao de acucar", "Pão de Açúcar", "Mercado"),
        Triple("assai", "Assaí", "Mercado"),
        Triple("atacadao", "Atacadão", "Mercado"),
        Triple("supermercado", "Supermercado", "Mercado"),
        Triple("hortifruti", "Hortifruti", "Mercado"),
        Triple("sams club", "Sam's Club", "Mercado"),
        Triple("uber", "Uber", "Transporte"),
        Triple("99app", "99", "Transporte"),
        Triple("99 pop", "99", "Transporte"),
        Triple("posto", "Posto", "Transporte"),
        Triple("ipiranga", "Ipiranga", "Transporte"),
        Triple("shell", "Shell", "Transporte"),
        Triple("estacionamento", "Estacionamento", "Transporte"),
        Triple("latam", "LATAM", "Transporte"),
        Triple("localiza", "Localiza", "Transporte"),
        Triple("netflix", "Netflix", "Assinaturas"),
        Triple("spotify", "Spotify", "Assinaturas"),
        Triple("disney", "Disney+", "Assinaturas"),
        Triple("globoplay", "Globoplay", "Assinaturas"),
        Triple("youtube", "YouTube", "Assinaturas"),
        Triple("chatgpt", "ChatGPT", "Assinaturas"),
        Triple("openai", "OpenAI", "Assinaturas"),
        Triple("adobe", "Adobe", "Assinaturas"),
        Triple("icloud", "iCloud", "Assinaturas"),
        Triple("deezer", "Deezer", "Assinaturas"),
        Triple("amazon", "Amazon", "Compras"),
        Triple("mercado livre", "Mercado Livre", "Compras"),
        Triple("shopee", "Shopee", "Compras"),
        Triple("shein", "Shein", "Compras"),
        Triple("magalu", "Magalu", "Compras"),
        Triple("renner", "Renner", "Compras"),
        Triple("riachuelo", "Riachuelo", "Compras"),
        Triple("centauro", "Centauro", "Compras"),
        Triple("netshoes", "Netshoes", "Compras"),
        Triple("drogasil", "Drogasil", "Saúde"),
        Triple("drogaria", "Drogaria", "Saúde"),
        Triple("ultrafarma", "Ultrafarma", "Saúde"),
        Triple("farmacia", "Farmácia", "Saúde"),
        Triple("hospital", "Hospital", "Saúde"),
        Triple("smart fit", "Smart Fit", "Saúde"),
        Triple("academia", "Academia", "Saúde"),
        Triple("cinema", "Cinema", "Lazer"),
        Triple("ingresso", "Ingresso", "Lazer"),
        Triple("steam", "Steam", "Lazer"),
        Triple("playstation", "PlayStation", "Lazer"),
        Triple("airbnb", "Airbnb", "Lazer"),
        Triple("hotel", "Hotel", "Lazer"),
        Triple("aluguel", "Aluguel", "Moradia"),
        Triple("condominio", "Condomínio", "Moradia"),
        Triple("enel", "Enel", "Moradia"),
        Triple("cemig", "Cemig", "Moradia"),
        Triple("sabesp", "Sabesp", "Moradia"),
        Triple("internet", "Internet", "Moradia"),
        Triple("escola", "Escola", "Educação"),
        Triple("faculdade", "Faculdade", "Educação"),
        Triple("curso", "Curso", "Educação"),
        Triple("udemy", "Udemy", "Educação"),
        Triple("alura", "Alura", "Educação"),
        Triple("iptu", "IPTU", "Impostos"),
        Triple("ipva", "IPVA", "Impostos"),
        Triple("detran", "Detran", "Impostos"),
        Triple("inss", "INSS", "Impostos")
    )

    private val GENERIC_CATEGORIES = listOf(
        "acougue" to "Mercado", "feira" to "Mercado", "mercearia" to "Mercado",
        "cafe" to "Alimentação", "bar " to "Alimentação", "doce" to "Alimentação",
        "onibus" to "Transporte", "metro" to "Transporte", "passagem" to "Transporte",
        "combustivel" to "Transporte", "gasolina" to "Transporte", "etanol" to "Transporte",
        "luz" to "Moradia", "energia" to "Moradia", "agua" to "Moradia", "gas " to "Moradia",
        "vivo" to "Moradia", "claro" to "Moradia", "tim " to "Moradia", "oi " to "Moradia",
        "clinica" to "Saúde", "laboratorio" to "Saúde", "dentista" to "Saúde",
        "livro" to "Educação", "livraria" to "Educação",
        "show" to "Lazer", "viagem" to "Lazer", "jogo" to "Lazer",
        "assinatura" to "Assinaturas", "mensalidade" to "Assinaturas",
        "imposto" to "Impostos", "taxa" to "Impostos", "tarifa" to "Serviços",
        "seguro" to "Serviços"
    )

    private val AMOUNT_CENTS = Regex("""R\$\s*(\d{1,3}(?:\.\d{3})*|\d+),(\d{2})""", RegexOption.IGNORE_CASE)
    private val AMOUNT_DOT = Regex("""R\$\s*(\d{1,3}(?:\.\d{3})*|\d+)\.(\d{2})(?!\d)""", RegexOption.IGNORE_CASE)
    private val AMOUNT_WHOLE = Regex("""R\$\s*(\d{1,3}(?:\.\d{3})*|\d+)(?![\d,.])""", RegexOption.IGNORE_CASE)
    private val MERCHANT_AFTER = Regex(
        """(?i)\b(?:em|no|na|para|de|do|da|com)\s+([A-Za-zÀ-ÿ0-9*][A-Za-zÀ-ÿ0-9 .,'&*\-]{1,44})"""
    )
    private val CARD_FINAL = Regex("""(?i)final\s*(\d{3,4})""")
    private val STOP = setOf(
        "sua conta", "conta", "cartao", "seu cartao", "pix", "debito", "credito",
        "voce", "aplicativo", "app", "banco", "agencia", "conta corrente", "conta poupanca",
        "de", "da", "do", "em", "no", "na", "para", "com", "a", "o", "e", "por"
    )

    private val GENERIC_BRANDS = setOf(
        "Padaria", "Posto", "Restaurante", "Supermercado", "Farmácia", "Drogaria",
        "Academia", "Hotel", "Cinema", "Curso", "Escola", "Faculdade", "Internet",
        "Aluguel", "Condomínio", "Estacionamento", "Lanchonete", "Pizzaria", "Mercado"
    )

    fun normalize(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFD)
            .replace(Regex("""\p{M}+"""), "")
            .lowercase(Locale("pt", "BR"))
            .replace(Regex("""\s+"""), " ")
            .trim()

    fun looksLikeMoney(text: String): Boolean =
        AMOUNT_CENTS.containsMatchIn(text) || AMOUNT_DOT.containsMatchIn(text) || text.contains("R\$")

    fun parse(title: String, body: String): ParsedTxn? {
        val raw = listOf(title, body).filter { it.isNotBlank() }.joinToString(" — ").trim()
        if (raw.isBlank()) return null
        val n = normalize(raw)
        if (IGNORE.any { n.contains(it) }) return null

        val amount = amountOf(raw) ?: return null

        var expenseHits = EXPENSE.count { n.contains(it) }
        var incomeHits = INCOME.count { n.contains(it) }
        val invoicePayment = n.contains("fatura") &&
            (n.contains("pagamento") || n.contains("paga") || n.contains("quitad"))
        var knownIncome = listOf("recebeu", "recebido", "creditado", "deposito", "estorno", "reembolso").any { n.contains(it) }
        if (invoicePayment) {
            expenseHits += 3
            incomeHits = 0
            knownIncome = false
        } else if (knownIncome) {
            incomeHits = maxOf(incomeHits, expenseHits + 1)
        }
        val directionKnown = expenseHits != 0 || incomeHits != 0
        val isExpense = when {
            expenseHits > incomeHits -> true
            incomeHits > expenseHits -> false
            else -> !knownIncome
        }

        val brand = KNOWN_MERCHANTS.firstOrNull { (key, _, _) ->
            n.contains(key) && (key.length > 4 || Regex("\\b${Regex.escape(key)}\\b").containsMatchIn(n))
        }
        val extracted = merchantFromText(raw)
        val merchant = when {
            extracted.isBlank() -> brand?.second.orEmpty()
            brand == null -> extracted
            brand.second in GENERIC_BRANDS && extracted.lowercase().startsWith(brand.second.lowercase()) -> extracted
            else -> brand.second
        }

        val category = when {
            brand != null -> brand.third
            !isExpense && (n.contains("salario") || n.contains("folha") || n.contains("pagamento recebido")) -> "Salário"
            !isExpense -> "Outras receitas"
            else -> GENERIC_CATEGORIES.firstOrNull { n.contains(it.first) }?.second ?: "Outros"
        }

        val method = when {
            n.contains("pix") -> "Pix"
            n.contains("credito") -> "Crédito"
            n.contains("debito") -> "Débito"
            n.contains("boleto") -> "Boleto"
            n.contains("ted") || n.contains("doc ") -> "TED"
            else -> null
        }
        val card = CARD_FINAL.find(raw)?.groupValues?.getOrNull(1)

        var confidence = 0.45f
        if (directionKnown) confidence += 0.25f
        if (merchant.isNotBlank()) confidence += 0.12f
        if (brand != null) confidence += 0.1f
        if (method != null) confidence += 0.05f
        if (expenseHits > 0 && incomeHits > 0) confidence -= 0.3f
        confidence = min(confidence, 0.97f)

        val display = merchant.ifBlank {
            when {
                n.contains("saque") -> "Saque"
                n.contains("tarifa") -> "Tarifa"
                isExpense -> "Compra"
                else -> "Recebimento"
            }
        }
        val merchantLabel = if (card != null) "$display (final $card)" else display

        return ParsedTxn(
            amountCents = amount,
            isExpense = isExpense,
            directionKnown = directionKnown,
            merchant = merchantLabel,
            category = category,
            method = method,
            confidence = confidence
        )
    }

    private fun amountOf(raw: String): Long? {
        AMOUNT_CENTS.find(raw)?.let { m ->
            val intPart = m.groupValues[1].replace(".", "")
            return intPart.toLongOrNull()?.times(100)?.plus(m.groupValues[2].toLong())
        }
        AMOUNT_DOT.find(raw)?.let { m ->
            val intPart = m.groupValues[1].replace(".", "")
            return intPart.toLongOrNull()?.times(100)?.plus(m.groupValues[2].toLong())
        }
        AMOUNT_WHOLE.find(raw)?.let { m ->
            return m.groupValues[1].replace(".", "").toLongOrNull()?.times(100)
        }
        return null
    }

    private fun merchantFromText(raw: String): String {
        for (match in MERCHANT_AFTER.findAll(raw)) {
            val value = match.groupValues[1]
            if (value.startsWith("R$") || value.startsWith("r$")) continue
            val candidate = cleanMerchant(value)
            println("CAND value=[$value] cleaned=[$candidate]")
            if (candidate.isNotBlank() && normalize(candidate) !in STOP) return candidate
        }
        val amountStart = AMOUNT_CENTS.find(raw)?.range?.first
            ?: AMOUNT_DOT.find(raw)?.range?.first
            ?: AMOUNT_WHOLE.find(raw)?.range?.first
        if (amountStart != null) {
            val before = raw.substring(0, amountStart)
                .split('.', '!', ';', '\n').lastOrNull().orEmpty().trim()
            val cleaned = cleanMerchant(before)
            println("PREFIX before=[$before] cleaned=[$cleaned]")
            if (cleaned.isNotBlank() && normalize(cleaned) !in STOP && cleaned.split(" ").size <= 6) {
                return cleaned
            }
        }
        return ""
    }

    private fun cleanMerchant(value: String): String {
        var s = value
        s = s.replace(Regex("""(?i)\s+(aprovad[ao]|realizad[ao]|efetuad[ao]|enviad[ao]|recebid[ao])(\s|$).*"""), "")
        s = s.replace(Regex("""(?i)\s+(no|na)\s+(cr[ée]dito|d[ée]bito|conta|cart[ãa]o)(\s|$).*"""), "")
        s = s.replace(Regex("""(?i)\s+(as|às|dia|via|usando|pelo|no app|pelo app)\s.*"""), "")
        s = s.replace(Regex("""(?i)\s*\(final.*"""), "")
        s = s.replace(Regex("""(?i)\s+r?\$.*"""), "")
        s = s.replace(Regex("""(?i)^(compra|pagamento|pix|transfer[êe]ncia|ted|doc|saque|tarifa|recebimento)\b\s*"""), "")
        s = s.replace(Regex("""(?i)^(cr[ée]dito|d[ée]bito|conta|cart[ãa]o)\b\s*"""), "")
        s = s.replace(Regex("""(?i)^(aprovad[ao]|recebid[ao]|enviad[ao]|realizad[ao])\b\s*"""), "")
        s = s.replace(Regex("""(?i)^(no|na|de|do|da|em|para|com)\s+"""), "")
        s = s.replace(Regex("""(?i)\s+(de|da|do|em|no|na|para)\s*r?$"""), "")
        s = s.trim(' ', ':', '-', ',', '.', '—')
        if (s.length < 2) return ""
        return s.take(48)
    }
}
