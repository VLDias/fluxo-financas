package app.fluxo.financas.util

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import app.fluxo.financas.R
import app.fluxo.financas.data.Money
import app.fluxo.financas.data.Txn
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

object CsvExporter {

    fun export(context: Context, txns: List<Txn>): File {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm"))
        val file = File(dir, "fluxo-$stamp.csv")
        file.bufferedWriter(Charsets.UTF_8).use { writer ->
            writer.write("\uFEFF")
            writer.write("data;tipo;valor;categoria;descricao;conta;origem;observacao\n")
            txns.sortedBy { it.date }.forEach { t ->
                writer.write(
                    listOf(
                        Dates.fullDate(t.date),
                        if (t.isExpense) "Despesa" else "Receita",
                        Money.formatPlain(t.amountCents).replace(",", "."),
                        t.categoryName,
                        t.merchant,
                        t.accountName,
                        when (t.source) {
                            "notificacao" -> "Notificação"
                            "salario" -> "Salário"
                            else -> "Manual"
                        },
                        t.note.replace("\n", " ").replace(";", ",")
                    ).joinToString(";")
                )
                writer.write("\n")
            }
        }
        return file
    }

    fun share(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.csv_share_title))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, context.getString(R.string.csv_share_title)))
    }
}
