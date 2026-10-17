#!/usr/bin/env python3
"""Generate synthetic bank statements (printable HTML) + the ground-truth JSON they must extract to.

No PII: invented bank names, cardholders, merchants. Two languages (Spanish, English). For each
statement the script writes two files rendered from the same canonical data, so they always agree:

    <name>.html  -- print to PDF (A4) and feed to the extractor
    <name>.json  -- the exact data the extractor must produce (for automated tests)

Conventions match AiStatementExtractor / RealBedrockClient.buildExtractionPrompt:
  - amount is SIGNED: negative = money out (charge/debit), positive = money in (payment/credit/refund).
  - every monetary field (amount, opening/closing/runningBalance) is a 2-decimal dot-separated STRING
    ("-35.00"), locale-independent; only the rendered HTML uses the ES comma / EN dot.
  - type in {purchase, payment, fee, interest, refund}.
  - runningBalance is set only when the statement prints a per-row balance (debit account), else null.
  - reconciliation: openingBalance + sum(amount) = closingBalance (sign-agnostic), or the running chain.

Reproduces the structures that break parsing: trailing "CR" credit marker, FX rows whose rate/fee live
in the description (printed total already includes the fee), FX detail shown inline in a column on some
rows and on a wrapped continuation line on others, two interleaved cardholders, embedded non-transaction
rows, separate CHARGES/CREDITS columns where the sign comes from the COLUMN not the wording, pseudo
balance rows, per-page header "noise" blocks, signed amounts + running balance, dates with no year, and
the ES comma-decimal vs EN dot-decimal number formats.

Usage:
    python3 gen_test_statements.py --out ./out [--lang es|en|both] [--seed 42]
Then open each .html and Print -> Save as PDF (A4), or headless Chrome / weasyprint:
    for f in out/**/*.html; do chromium --headless --print-to-pdf="${f%.html}.pdf" "$f"; done
"""

import argparse
import json
import os
import random
from datetime import date, timedelta

N_AMEX = 70   # rows per statement -> ~2 A4 pages; tune to taste
N_TDC = 72
N_TDD = 44

LOCALES = {
    "es": {
        "cur": "EUR", "comma_decimal": True, "d_dot": "%d.%m.%y", "d_slash": "%d/%m",
        "testdoc": "documento de prueba, sin datos reales", "page": ("Pagina", "de"),
        "banks": {
            "amex1": ("MERIDIAN EXPRESS", "Meridian Express Card, S.A."),
            "tdc": ("BANCO ALTAMAR", "Banco Altamar, S.A."),
            "tdd": ("CAJA HORIZONTE", "Caja Horizonte, S.A.")},
        "cardholders": ["LUCIA MENDOZA SORIA", "DANIEL PRADO ESCUDERO"],
        "cities": ["MADRID", "SEVILLA", "VALENCIA", "BILBAO", "ZARAGOZA", "MALAGA", "A CORUNA"],
        "merchants": ["SUPERMERCADO ESTRELLA", "CAFETERIA CENTRAL", "GASOLINERA NORTE", "FARMACIA SOL",
                      "LIBRERIA PAGINA", "RESTAURANTE EL PATIO", "FERRETERIA OMEGA", "PANADERIA LA ESPIGA",
                      "TIENDA DEPORTES ATLAS", "CINE LUMIERE", "PELUQUERIA VENUS", "FLORISTERIA IRIS",
                      "ELECTRONICA DELTA", "PAPELERIA CLIP", "CLINICA DENTAL VIDA", "HOTEL MIRADOR"],
        "fx": [("GLOBALSTREAM SUSCRIPCION", "Dolar USA", 0.92), ("TIENDA ONLINE LONDRES", "Libra Esterlina", 1.17),
               ("MERCADO CDMX", "Peso Mexicano", 0.048)],
        "tdd_concepts": [("TRASPASO A FAVOR DE TERCEROS", -1, "purchase"), ("BIZUM RECIBIDO", +1, "refund"),
                         ("ADEUDO RECIBO LUZ", -1, "purchase"), ("NOMINA MENSUAL", +1, "refund"),
                         ("BIZUM ENVIADO", -1, "purchase"), ("ADEUDO SEGURO HOGAR", -1, "purchase"),
                         ("TRASPASO RECIBIDO", +1, "refund"), ("PAGO MOVIL TPV", -1, "purchase"),
                         ("DEVOLUCION RECIBO", +1, "refund"), ("ADEUDO TELEFONIA", -1, "purchase"),
                         ("RETIRADA CAJERO", -1, "purchase"), ("INGRESO EFECTIVO", +1, "refund")],
        "s": {
            "amex_doc": "Estado de cuenta",
            "h_txndate": "Fecha de Transaccion", "h_procdate": "Fecha de Proceso", "h_details": "Detalles",
            "h_foreign": "Gasto en el extranjero",
            "h_amount": "Importe en {cur}",
            "payment_prev": "RECIBO ENVIADO A SU BANCO - GRACIAS POR SU PAGO",
            "subholder": "Nuevos cargos realizados por {h} (titular adicional)",
            "financial": "TRANSACCIONES FINANCIERAS", "annual_fee": "CUOTA ANUAL TARJETA",
            "welcome": "ABONO PROMOCIONAL BIENVENIDA",
            "fx_note": "Cambio aplicado a la divisa {rate} + Comision conversion moneda {com}",
            "fx_note_wrapped": "Gasto en el extranjero {amt} {cur} - Cambio {rate} + Comision conversion {com}",
            "total_charges": "Total de cargos del periodo", "total_payments": "Total de pagos y abonos",
            "sum_holder": "Titular", "sum_card": "Tarjeta", "sum_period": "Periodo",
            "sum_due": "Fecha limite de pago", "sum_prevbal": "Saldo anterior", "sum_newbal": "Nuevo saldo",
            "banner_amex": "Resumen de tu tarjeta &mdash; {a} a {b}", "right_amex": "Estado de cuenta",
            "fineprint_amex": ('Operaciones en divisa convertidas al cambio indicado, comision incluida en el '
                               'importe. "CR" indica un abono a su favor.'),
            "tdc_doc": "Extracto de tarjeta de credito",
            "h_date": "FECHA OPERACION", "h_concept": "CONCEPTO", "h_location": "LOCALIDAD",
            "h_charges": "CARGOS", "h_credits": "ABONOS",
            "prev_pay": "RECIBO MES ANTERIOR TARJETA CREDITO",
            "reembolso": "REEMBOLSO DEL SALDO A SU FAVOR", "pdm": "PDM PAGOS SEDE ELECTRONICA",
            "devolucion": "DEVOLUCION COMPRA ONLINE",
            "saldo_ant": "SALDO ANTERIOR", "saldo_pend": "SALDO PENDIENTE", "total_movs": "TOTAL MOVIMIENTOS",
            "n_office": "OFICINA", "n_date": "FECHA EXTRACTO", "n_code": "CODIGO", "n_account": "CUENTA", "n_dc": "D.C.",
            "banner_tdc": "Movimientos de tarjeta &mdash; {a} a {b} &mdash; Tarjeta ****{l4}",
            "right_tdc": "Extracto de tarjeta de credito",
            "fineprint_tdc": ("CARGOS: importes a su cargo. ABONOS: importes a su favor. "
                              "El signo del movimiento lo determina la columna, no el concepto."),
            "tdd_doc": "Extracto de cuenta corriente",
            "h_foper": "F.Oper.", "h_fvalor": "F.Valor", "h_concept_tdd": "Concepto",
            "h_amount_tdd": "Importe ({cur})", "h_balance": "Saldo ({cur})",
            "saldo_ini": "SALDO INICIAL", "saldo_fin": "SALDO FINAL",
            "sum_open": "Saldo inicial", "sum_close": "Saldo final",
            "banner_tdd": "Cuenta de debito &mdash; movimientos del periodo (ano {y})", "right_tdd": "Extracto de cuenta",
            "fineprint_tdd": ("Importes con signo: negativo = cargo, positivo = abono. "
                              "Fechas DD/MM; el ano corresponde al periodo del extracto."),
        },
    },
    "en": {
        "cur": "USD", "comma_decimal": False, "d_dot": "%m/%d/%y", "d_slash": "%m/%d",
        "testdoc": "test document, no real data", "page": ("Page", "of"),
        "banks": {
            "amex1": ("SUMMIT EXPRESS", "Summit Express Card, Inc."),
            "tdc": ("NORTHGATE BANK", "Northgate Bank, N.A."),
            "tdd": ("RIVERSTONE BANK", "Riverstone Bank, N.A.")},
        "cardholders": ["EMMA CARTER REED", "JAMES MORGAN HALE"],
        "cities": ["NEW YORK", "CHICAGO", "BOSTON", "SEATTLE", "DENVER", "MIAMI", "AUSTIN"],
        "merchants": ["GROCERY MART", "CENTRAL CAFE", "NORTH FUEL STATION", "SUNRISE PHARMACY",
                      "PAGE BOOKSTORE", "THE PATIO DINER", "OMEGA HARDWARE", "GOLDEN BAKERY",
                      "ATLAS SPORTS", "LUMIERE CINEMA", "VENUS SALON", "IRIS FLORIST",
                      "DELTA ELECTRONICS", "CLIP STATIONERY", "VIDA DENTAL CLINIC", "HILLVIEW HOTEL"],
        "fx": [("GLOBALSTREAM SUBSCRIPTION", "Euro", 1.08), ("LONDON ONLINE SHOP", "British Pound", 1.27),
               ("MEXICO CITY MARKET", "Mexican Peso", 0.052)],
        "tdd_concepts": [("TRANSFER TO THIRD PARTY", -1, "purchase"), ("INSTANT PAYMENT RECEIVED", +1, "refund"),
                         ("DIRECT DEBIT - ELECTRICITY", -1, "purchase"), ("PAYROLL DEPOSIT", +1, "refund"),
                         ("INSTANT PAYMENT SENT", -1, "purchase"), ("DIRECT DEBIT - HOME INSURANCE", -1, "purchase"),
                         ("INCOMING TRANSFER", +1, "refund"), ("CARD PAYMENT - POS", -1, "purchase"),
                         ("REFUND RECEIVED", +1, "refund"), ("DIRECT DEBIT - PHONE", -1, "purchase"),
                         ("ATM WITHDRAWAL", -1, "purchase"), ("CASH DEPOSIT", +1, "refund")],
        "s": {
            "amex_doc": "Account Statement",
            "h_txndate": "Transaction Date", "h_procdate": "Posting Date", "h_details": "Details",
            "h_foreign": "Foreign Spend",
            "h_amount": "Amount in {cur}",
            "payment_prev": "PAYMENT RECEIVED - THANK YOU",
            "subholder": "New charges by {h} (additional cardholder)",
            "financial": "FINANCIAL TRANSACTIONS", "annual_fee": "ANNUAL MEMBERSHIP FEE",
            "welcome": "WELCOME PROMOTIONAL CREDIT",
            "fx_note": "Exchange rate {rate} + foreign transaction fee {com}",
            "fx_note_wrapped": "Foreign spend {amt} {cur} - Exchange rate {rate} + fee {com}",
            "total_charges": "Total charges this period", "total_payments": "Total payments and credits",
            "sum_holder": "Cardholder", "sum_card": "Card number", "sum_period": "Statement period",
            "sum_due": "Payment due date", "sum_prevbal": "Previous balance", "sum_newbal": "New balance",
            "banner_amex": "Your card summary &mdash; {a} to {b}", "right_amex": "Account statement",
            "fineprint_amex": ('Foreign transactions are converted at the shown rate, fee included in the amount. '
                               '"CR" marks a credit in your favor.'),
            "tdc_doc": "Credit Card Statement",
            "h_date": "TRANSACTION DATE", "h_concept": "DESCRIPTION", "h_location": "LOCATION",
            "h_charges": "CHARGES", "h_credits": "CREDITS",
            "prev_pay": "PREVIOUS STATEMENT BALANCE PAYMENT",
            "reembolso": "REFUND OF CREDIT BALANCE", "pdm": "GOV PAYMENTS E-PORTAL",
            "devolucion": "ONLINE PURCHASE REFUND",
            "saldo_ant": "PREVIOUS BALANCE", "saldo_pend": "BALANCE DUE", "total_movs": "TOTAL",
            "n_office": "BRANCH", "n_date": "STATEMENT DATE", "n_code": "CODE", "n_account": "ACCOUNT", "n_dc": "CD",
            "banner_tdc": "Card transactions &mdash; {a} to {b} &mdash; Card ****{l4}",
            "right_tdc": "Credit card statement",
            "fineprint_tdc": ("CHARGES: amounts billed to you. CREDITS: amounts in your favor. "
                              "The sign of each entry is set by its column, not by the wording."),
            "tdd_doc": "Checking Account Statement",
            "h_foper": "Post Date", "h_fvalor": "Value Date", "h_concept_tdd": "Description",
            "h_amount_tdd": "Amount ({cur})", "h_balance": "Balance ({cur})",
            "saldo_ini": "OPENING BALANCE", "saldo_fin": "CLOSING BALANCE",
            "sum_open": "Opening balance", "sum_close": "Closing balance",
            "banner_tdd": "Debit account &mdash; statement period (year {y})", "right_tdd": "Account statement",
            "fineprint_tdd": ("Signed amounts: negative = debit, positive = credit. "
                              "Dates shown as month/day; the year is the statement period."),
        },
    },
}


def money(x, loc):
    s = f"{abs(x):,.2f}"
    if loc["comma_decimal"]:
        s = s.replace(",", "@").replace(".", ",").replace("@", ".")
    return ("-" + s) if x < 0 else s


def money_json(x):
    """Ground-truth money: a 2-decimal, dot-separated string (locale-independent),
    or None when absent. The HTML keeps the per-language comma/dot via money()."""
    return None if x is None else f"{x:.2f}"


def mag(lo, hi):
    return round(random.uniform(lo, hi), 2)


def dates_from(start, n):
    out, d = [], start
    for _ in range(n):
        d = d + timedelta(days=random.randint(0, 1))
        out.append(d)
    return out


def txn(d, amount, desc, ttype, running=None, **disp):
    r = dict(kind="txn", date=d, amount=round(amount, 2), description=desc, type=ttype, running=running)
    r.update(disp)
    return r


CSS = """
* { box-sizing: border-box; }
body { font-family: "Helvetica Neue", Arial, sans-serif; font-size: 10px; color: #1a1a1a; margin: 0; }
.banner { border-bottom: 3px solid #003366; padding-bottom: 6px; margin-bottom: 10px; }
.brand { font-size: 22px; font-weight: 800; letter-spacing: 1px; color: #003366; }
.brand .accent { color: #c0392b; }
.brand-meta { float: right; text-align: right; font-size: 9px; color: #444; }
.brand-sub { margin-top: 3px; font-size: 11px; font-weight: 600; color: #222; }
.summary { width: 100%; border-collapse: collapse; margin: 8px 0 12px; }
.summary td { border: 1px solid #ccc; padding: 4px 6px; }
.summary .lbl { background: #eef2f7; font-weight: 600; width: 20%; }
.noise { width: 100%; border-collapse: collapse; margin: 4px 0; font-size: 9px; }
.noise td { border: 1px solid #d5d5d5; padding: 2px 5px; }
.noise .lbl { background: #f3f3f3; font-weight: 600; }
table.txns { width: 100%; border-collapse: collapse; }
table.txns thead th { background: #003366; color: #fff; font-size: 9px; text-align: left;
  padding: 4px 6px; border: 1px solid #002244; }
table.txns td { padding: 3px 6px; border-bottom: 1px solid #e3e3e3; vertical-align: top; }
table.txns tr { break-inside: avoid; }
table.txns tr.section td { background: #dfe7f0; font-weight: 700; }
table.txns tr.pseudo td { background: #fbf7e8; font-weight: 600; }
table.txns tr.totals td { border-top: 2px solid #003366; font-weight: 700; }
.num { text-align: right; font-variant-numeric: tabular-nums; white-space: nowrap; }
.cr { color: #1a7f37; } .neg { color: #c0392b; }
.fx-note { color: #555; font-size: 8.5px; }
.fineprint { margin-top: 14px; font-size: 8px; color: #777; line-height: 1.35; }
"""


def shell(loc, bank, legal, doc_title, banner, body):
    pg1, pg2 = loc["page"]
    page_css = f"""
    @page {{ size: A4; margin: 24mm 12mm 16mm 12mm;
      @top-left {{ content: "{bank}"; font: 700 10px Arial; color: #003366; }}
      @top-right {{ content: "{doc_title}"; font: 9px Arial; color: #555; }}
      @bottom-left {{ content: "{legal} - {loc['testdoc']}"; font: 7px Arial; color: #999; }}
      @bottom-right {{ content: "{pg1} " counter(page) " {pg2} " counter(pages); font: 8px Arial; color: #555; }}
    }}"""
    return (f'<!doctype html>\n<html><head><meta charset="utf-8"><title>{bank} {doc_title}</title>'
            f'<style>{page_css}{CSS}</style></head><body>{banner}{body}</body></html>')


def banner_html(bank, sub, right):
    p = bank.split(" ", 1)
    accent = f'<span class="accent"> {p[1]}</span>' if len(p) > 1 else ""
    return (f'<div class="banner"><div class="brand-meta">{right}</div>'
            f'<div class="brand">{p[0]}{accent}</div><div class="brand-sub">{sub}</div></div>')


def to_json(meta, lines):
    txns = [l for l in lines if l["kind"] == "txn"]
    return {
        "metadata": {
            "bankName": meta["bank"], "productType": meta["product"], "currency": meta["cur"],
            "accountLast4": meta["last4"], "periodStart": meta["start"].isoformat(),
            "periodEnd": meta["end"].isoformat(), "openingBalance": money_json(meta["opening"]),
            "closingBalance": money_json(meta["closing"])},
        "transactions": [
            {"date": t["date"].isoformat(), "amount": money_json(t["amount"]), "description": t["description"],
             "runningBalance": money_json(t["running"]), "type": t["type"]} for t in txns],
        "reconciled": True,
    }


def reconciles(meta, lines):
    s = round(sum(l["amount"] for l in lines if l["kind"] == "txn"), 2)
    o, c = meta["opening"], meta["closing"]
    return min(abs(s - (c - o)), abs(s - (o - c))) < 0.01


# ---------------------------------------------------------------- AMEX

def build_charge_card(loc, key, start):
    S = loc["s"]
    def mon(x): return money(x, loc)
    bank, legal = loc["banks"][key]
    primary, secondary = loc["cardholders"]
    cities, merchants, fxpool = loc["cities"], loc["merchants"], loc["fx"]
    opening = mag(400, 1500)
    last4 = f"{random.randint(1000, 9999)}"
    sub_at, fx_at = N_AMEX // 2, [5, N_AMEX // 2 + 2, N_AMEX - 3]
    # FX detail shows up in two places within the one statement: inline in the
    # foreign-spend column on some rows, on a wrapped continuation line under the
    # description on others. The ground-truth JSON is identical either way.
    fx_inline = {pos: (k % 2 == 0) for k, pos in enumerate(fx_at)}
    lines = [txn(start, +opening, S["payment_prev"], "payment", f_proc=start + timedelta(days=1), fx="", cr=True)]

    for i, d in enumerate(dates_from(start + timedelta(days=2), N_AMEX)):
        if i == sub_at:
            lines.append(dict(kind="subholder", holder=secondary))
        if i in fx_inline:
            name, cur, rate = random.choice(fxpool)
            foreign = mag(30, 400)
            base = round(foreign * rate, 2)
            com = round(base * 0.03, 2)
            total = round(base + com, 2)
            if fx_inline[i]:
                fxcol = f"{mon(foreign)} {cur}"
                note = S["fx_note"].format(rate=f"{rate:.4f}", com=mon(com))
            else:
                fxcol = ""
                note = S["fx_note_wrapped"].format(amt=mon(foreign), cur=cur, rate=f"{rate:.4f}", com=mon(com))
            lines.append(txn(d, -total, f"{name}  {cities[i % len(cities)]}", "purchase",
                             f_proc=d + timedelta(days=1), fx=fxcol, cr=False, note=note))
        else:
            lines.append(txn(d, -mag(6, 180), f"{random.choice(merchants)}  {cities[i % len(cities)]}",
                             "purchase", f_proc=d + timedelta(days=1), fx="", cr=False))

    lines.append(dict(kind="section", label=S["financial"]))
    lines.append(txn(start + timedelta(days=15), -mag(20, 60), S["annual_fee"], "fee",
                     f_proc=start + timedelta(days=15), fx="", cr=False))
    lines.append(txn(start + timedelta(days=16), +mag(5, 25), S["welcome"], "refund",
                     f_proc=start + timedelta(days=16), fx="", cr=True))

    closing = round(opening - sum(l["amount"] for l in lines if l["kind"] == "txn"), 2)  # amount owed
    end = max(l["date"] for l in lines if l["kind"] == "txn") + timedelta(days=3)
    meta = dict(bank=bank, product="credit", cur=loc["cur"], last4=last4, start=start, end=end,
                opening=opening, closing=closing)

    headers = [S["h_txndate"], S["h_procdate"], S["h_details"], S["h_foreign"], S["h_amount"].format(cur=loc["cur"])]
    nc = len(headers)

    def row(l):
        if l["kind"] == "subholder":
            return f'<tr class="section"><td colspan="{nc}">{S["subholder"].format(h=l["holder"])}</td></tr>'
        if l["kind"] == "section":
            return f'<tr class="section"><td colspan="{nc}">{l["label"]}</td></tr>'
        det = l["description"] + (f'<br><span class="fx-note">{l["note"]}</span>' if l.get("note") else "")
        imp = mon(abs(l["amount"])) + (' <span class="cr">CR</span>' if l["cr"] else "")
        fxc = f'<td class="num">{l["fx"]}</td>'
        return (f'<tr><td>{l["date"].strftime(loc["d_dot"])}</td><td>{l["f_proc"].strftime(loc["d_dot"])}</td>'
                f'<td>{det}</td>{fxc}<td class="num">{imp}</td></tr>')

    thead = "<thead><tr>" + "".join(f"<th>{h}</th>" for h in headers) + "</tr></thead>"
    charges = round(sum(-l["amount"] for l in lines if l["kind"] == "txn" and l["amount"] < 0), 2)
    credits = round(sum(l["amount"] for l in lines if l["kind"] == "txn" and l["amount"] > 0), 2)
    tbody = "".join(row(l) for l in lines)
    tbody += f'<tr class="totals"><td colspan="{nc-1}">{S["total_charges"]}</td><td class="num">{mon(charges)}</td></tr>'
    tbody += (f'<tr class="totals"><td colspan="{nc-1}">{S["total_payments"]}</td>'
              f'<td class="num">{mon(credits)} <span class="cr">CR</span></td></tr>')
    summary = f"""<table class="summary">
      <tr><td class="lbl">{S["sum_holder"]}</td><td>{primary}</td>
          <td class="lbl">{S["sum_card"]}</td><td>XXXX-XXXXXX-X{last4}</td></tr>
      <tr><td class="lbl">{S["sum_period"]}</td><td>{start.strftime(loc["d_dot"])} - {end.strftime(loc["d_dot"])}</td>
          <td class="lbl">{S["sum_due"]}</td><td>{(end+timedelta(days=20)).strftime(loc["d_dot"])}</td></tr>
      <tr><td class="lbl">{S["sum_prevbal"]}</td><td class="num">{mon(opening)} {loc["cur"]}</td>
          <td class="lbl">{S["sum_newbal"]}</td><td class="num"><b>{mon(closing)} {loc["cur"]}</b></td></tr></table>"""
    body = summary + f'<table class="txns">{thead}<tbody>{tbody}</tbody></table>' + f'<div class="fineprint">{S["fineprint_amex"]}</div>'
    banner = banner_html(bank, S["banner_amex"].format(a=start.strftime(loc["d_dot"]), b=end.strftime(loc["d_dot"])), S["right_amex"])
    return shell(loc, bank, legal, S["amex_doc"], banner, body), to_json(meta, lines), reconciles(meta, lines)


# ---------------------------------------------------------------- BBVA TDC

def build_tdc(loc, key, start):
    S = loc["s"]
    def mon(x): return money(x, loc)
    bank, legal = loc["banks"][key]
    cities, merchants = loc["cities"], loc["merchants"]
    opening = mag(300, 1200)
    last4 = f"{random.randint(1000, 9999)}"
    lines = [dict(kind="pseudo", label=S["saldo_ant"], value=opening),
             txn(start, +opening, S["prev_pay"], "payment", city="", col="abono")]
    reembolso_at, pagos_at, devol_at = 7, N_TDC // 2, N_TDC - 5

    for i, d in enumerate(dates_from(start + timedelta(days=1), N_TDC)):
        if i == reembolso_at:
            lines.append(txn(d, -mag(10, 40), S["reembolso"], "purchase", city=random.choice(cities), col="cargo")); continue
        if i == pagos_at:
            lines.append(txn(d, -mag(10, 60), S["pdm"], "purchase", city=random.choice(cities), col="cargo")); continue
        if i == devol_at:
            lines.append(txn(d, +mag(15, 70), S["devolucion"], "refund", city=random.choice(cities), col="abono")); continue
        lines.append(txn(d, -mag(5, 150), random.choice(merchants), "purchase", city=random.choice(cities), col="cargo"))

    closing = round(opening - sum(l["amount"] for l in lines if l["kind"] == "txn"), 2)  # amount owed
    lines.append(dict(kind="pseudo", label=S["saldo_pend"], value=closing))
    end = max(l["date"] for l in lines if l["kind"] == "txn") + timedelta(days=3)
    meta = dict(bank=bank, product="credit", cur=loc["cur"], last4=last4, start=start, end=end,
                opening=opening, closing=closing)

    def row(l):
        if l["kind"] == "pseudo":
            return f'<tr class="pseudo"><td></td><td>{l["label"]}</td><td></td><td class="num"></td><td class="num">{mon(l["value"])}</td></tr>'
        cargo = mon(abs(l["amount"])) if l["col"] == "cargo" else ""
        abono = mon(abs(l["amount"])) if l["col"] == "abono" else ""
        return (f'<tr><td>{l["date"].strftime(loc["d_dot"])}</td><td>{l["description"]}</td><td>{l["city"]}</td>'
                f'<td class="num neg">{cargo}</td><td class="num cr">{abono}</td></tr>')

    thead = (f'<thead><tr><th>{S["h_date"]}</th><th>{S["h_concept"]}</th><th>{S["h_location"]}</th>'
             f'<th>{S["h_charges"]}</th><th>{S["h_credits"]}</th></tr></thead>')
    cargos = round(sum(-l["amount"] for l in lines if l["kind"] == "txn" and l["col"] == "cargo"), 2)
    abonos = round(sum(l["amount"] for l in lines if l["kind"] == "txn" and l["col"] == "abono"), 2)
    tbody = "".join(row(l) for l in lines)
    tbody += (f'<tr class="totals"><td></td><td>{S["total_movs"]}</td><td></td>'
              f'<td class="num">{mon(cargos)}</td><td class="num">{mon(abonos)}</td></tr>')
    noise = (f'<table class="noise"><tr><td class="lbl">{S["n_office"]}</td><td>0412</td>'
             f'<td class="lbl">{S["n_date"]}</td><td>{end.strftime(loc["d_dot"])}</td></tr></table>'
             f'<table class="noise"><tr><td class="lbl">{S["n_code"]}</td><td>0182</td>'
             f'<td class="lbl">{S["n_account"]}</td><td>**** ** 0000</td><td class="lbl">{S["n_dc"]}</td><td>00</td></tr></table>')
    body = noise + f'<table class="txns">{thead}<tbody>{tbody}</tbody></table>' + f'<div class="fineprint">{S["fineprint_tdc"]}</div>'
    banner = banner_html(bank, S["banner_tdc"].format(a=start.strftime(loc["d_dot"]), b=end.strftime(loc["d_dot"]), l4=last4), S["right_tdc"])
    return shell(loc, bank, legal, S["tdc_doc"], banner, body), to_json(meta, lines), reconciles(meta, lines)


# ---------------------------------------------------------------- BBVA TDD

def build_tdd(loc, key, start):
    S = loc["s"]
    def mon(x): return money(x, loc)
    bank, legal = loc["banks"][key]
    saldo = mag(800, 3000)
    opening = round(saldo, 2)
    last4 = f"{random.randint(1000, 9999)}"
    pool = loc["tdd_concepts"]
    specs = [pool[i % len(pool)] for i in range(N_TDD)]
    lines = []
    for d, (con, sign, ttype) in zip(dates_from(start, N_TDD), specs):
        a = round(mag(20, 600) * sign, 2)
        saldo = round(saldo + a, 2)
        lines.append(txn(d, a, con, ttype, running=saldo, f_valor=d + timedelta(days=1)))
    closing = saldo
    end = max(l["date"] for l in lines) + timedelta(days=3)
    meta = dict(bank=bank, product="debit", cur=loc["cur"], last4=last4, start=start, end=end,
                opening=opening, closing=closing)

    thead = (f'<thead><tr><th>{S["h_foper"]}</th><th>{S["h_fvalor"]}</th><th>{S["h_concept_tdd"]}</th>'
             f'<th>{S["h_amount_tdd"].format(cur=loc["cur"])}</th><th>{S["h_balance"].format(cur=loc["cur"])}</th></tr></thead>')
    tbody = (f'<tr class="pseudo"><td></td><td></td><td>{S["saldo_ini"]}</td>'
             f'<td class="num"></td><td class="num">{mon(opening)}</td></tr>')
    for l in lines:
        cls = "neg" if l["amount"] < 0 else "cr"
        tbody += (f'<tr><td>{l["date"].strftime(loc["d_slash"])}</td><td>{l["f_valor"].strftime(loc["d_slash"])}</td>'
                  f'<td>{l["description"]}</td><td class="num {cls}">{mon(l["amount"])}</td>'
                  f'<td class="num">{mon(l["running"])}</td></tr>')
    tbody += (f'<tr class="totals"><td></td><td></td><td>{S["saldo_fin"]}</td><td class="num"></td>'
              f'<td class="num">{mon(closing)}</td></tr>')
    summary = (f'<table class="summary"><tr>'
               f'<td class="lbl">{S["sum_open"]}</td><td class="num">{mon(opening)} {loc["cur"]}</td>'
               f'<td class="lbl">{S["sum_close"]}</td><td class="num"><b>{mon(closing)} {loc["cur"]}</b></td></tr></table>')
    body = summary + f'<table class="txns">{thead}<tbody>{tbody}</tbody></table>' + f'<div class="fineprint">{S["fineprint_tdd"]}</div>'
    banner = banner_html(bank, S["banner_tdd"].format(y=start.year), S["right_tdd"])
    return shell(loc, bank, legal, S["tdd_doc"], banner, body), to_json(meta, lines), True


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--out", default="out")
    ap.add_argument("--lang", choices=["es", "en", "both"], default="both")
    ap.add_argument("--seed", type=int, default=42)
    args = ap.parse_args()

    langs = ["es", "en"] if args.lang == "both" else [args.lang]
    for lang in langs:
        loc = LOCALES[lang]
        random.seed(args.seed)  # same seed per language -> comparable data
        d = os.path.join(args.out, lang)
        os.makedirs(d, exist_ok=True)
        jobs = [
            ("charge_card", lambda: build_charge_card(loc, "amex1", date(2026, 1, 12))),
            ("credit_card", lambda: build_tdc(loc, "tdc", date(2026, 4, 5))),
            ("debit_account", lambda: build_tdd(loc, "tdd", date(2026, 3, 1))),
        ]
        for name, fn in jobs:
            html, j, ok = fn()
            open(os.path.join(d, name + ".html"), "w", encoding="utf-8").write(html)
            json.dump(j, open(os.path.join(d, name + ".json"), "w", encoding="utf-8"), ensure_ascii=False, indent=2)
            print(f"[{lang}] {name:14s} txns={len(j['transactions']):2d} {j['metadata']['productType']:6s} "
                  f"{j['metadata']['currency']} reconciles={ok}")

    print(f"\nOpen each .html and Print -> Save as PDF (A4). Headless Chrome / weasyprint:\n"
          f'  for f in {args.out}/**/*.html; do chromium --headless --print-to-pdf="${{f%.html}}.pdf" "$f"; done')


if __name__ == "__main__":
    main()
