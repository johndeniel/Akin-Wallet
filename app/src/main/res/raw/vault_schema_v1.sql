CREATE TABLE social_accounts (
id INTEGER PRIMARY KEY AUTOINCREMENT,
platform TEXT NOT NULL CHECK(length(trim(platform))>0),
username TEXT NOT NULL CHECK(length(trim(username))>0),
password TEXT NOT NULL DEFAULT '',pin TEXT NOT NULL DEFAULT '',created_at INTEGER NOT NULL CHECK(created_at>=0),updated_at INTEGER NOT NULL CHECK(updated_at>=0),deleted_at INTEGER NOT NULL DEFAULT 0 CHECK(deleted_at>=0));
CREATE TABLE bank_cards (
id INTEGER PRIMARY KEY AUTOINCREMENT,
card_type TEXT NOT NULL CHECK(card_type IN ('Debit','Credit','Prepaid')),
card_network TEXT NOT NULL CHECK(card_network IN ('Visa','MasterCard')),
bank_name TEXT NOT NULL CHECK(length(trim(bank_name))>0),
holder_name TEXT NOT NULL CHECK(length(trim(holder_name))>0),
card_number TEXT NOT NULL,expiry TEXT NOT NULL,cvv TEXT NOT NULL,pin TEXT NOT NULL,
design INTEGER NOT NULL DEFAULT 0 CHECK(design BETWEEN 0 AND 4),created_at INTEGER NOT NULL CHECK(created_at>=0),updated_at INTEGER NOT NULL CHECK(updated_at>=0),deleted_at INTEGER NOT NULL DEFAULT 0 CHECK(deleted_at>=0));
CREATE TABLE id_cards (
id INTEGER PRIMARY KEY AUTOINCREMENT,
id_type TEXT NOT NULL CHECK(length(trim(id_type))>0),
fields_json TEXT NOT NULL CHECK(json_valid(fields_json) AND json_type(fields_json)='object'),created_at INTEGER NOT NULL CHECK(created_at>=0),updated_at INTEGER NOT NULL CHECK(updated_at>=0),deleted_at INTEGER NOT NULL DEFAULT 0 CHECK(deleted_at>=0));
CREATE TABLE account_links (
account_id INTEGER NOT NULL REFERENCES social_accounts(id) ON DELETE CASCADE,
linked_account_id INTEGER NOT NULL REFERENCES social_accounts(id) ON DELETE CASCADE,
PRIMARY KEY(account_id,linked_account_id),CHECK(account_id<>linked_account_id));
CREATE INDEX idx_social_accounts_active ON social_accounts(updated_at DESC,id DESC) WHERE deleted_at=0;
CREATE INDEX idx_social_accounts_trash ON social_accounts(deleted_at DESC,id DESC) WHERE deleted_at>0;
CREATE INDEX idx_bank_cards_active ON bank_cards(updated_at DESC,id DESC) WHERE deleted_at=0;
CREATE INDEX idx_bank_cards_trash ON bank_cards(deleted_at DESC,id DESC) WHERE deleted_at>0;
CREATE INDEX idx_id_cards_active ON id_cards(updated_at DESC,id DESC) WHERE deleted_at=0;
CREATE INDEX idx_id_cards_trash ON id_cards(deleted_at DESC,id DESC) WHERE deleted_at>0;
CREATE INDEX idx_account_links_linked ON account_links(linked_account_id);
PRAGMA application_id=1095456305;
