package net.wiringbits.core

import net.wiringbits.repositories.*
import play.api.db.Database

case class RepositoryComponents(
    database: Database,
    users: UsersRepository,
    userTokens: UserTokensRepository,
    userLogs: UserLogsRepository,
    backgroundJobs: BackgroundJobsRepository,
    categories: CategoriesRepository,
    merchants: MerchantsRepository,
    expenses: ExpensesRepository,
    documents: DocumentsRepository,
    textractCache: TextractCacheRepository,
    bankStatements: BankStatementRepository,
    bankStatementDrafts: BankStatementDraftRepository,
    importRules: ImportRuleRepository,
    receipts: ReceiptRepository,
    refunds: RefundsRepository,
    savings: SavingsRepository,
    textractJobs: TextractJobRepository,
    telegram: TelegramRepository,
    telegramReceipt: TelegramReceiptRepository,
    mcp: McpRepository
)
