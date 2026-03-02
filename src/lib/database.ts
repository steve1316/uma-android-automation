import * as SQLite from "expo-sqlite"
import { startTiming } from "./performanceLogger"
import { logWithTimestamp, logErrorWithTimestamp } from "./logger"

// The schema for the database.
export interface DatabaseSettings {
    /** The unique identifier for the setting. */
    id: number
    /** The category of the setting. */
    category: string
    /** The key of the setting. */
    key: string
    /** The value of the setting. */
    value: string
    /** The timestamp of when the setting was last updated. */
    updated_at: string
}

export interface DatabaseRace {
    /** The unique identifier for the race. */
    id: number
    /** The key of the race. */
    key: string
    /** The name of the race. */
    name: string
    /** The date of the race. */
    date: string
    /** The race track of the race. */
    raceTrack: string
    /** The course of the race. */
    course: string | null
    /** The direction of the race. */
    direction: string
    /** The grade of the race. */
    grade: string
    /** The terrain of the race. */
    terrain: string
    /** The distance type of the race. */
    distanceType: string
    /** The distance of the race in meters. */
    distanceMeters: number
    /** The number of fans for the race. */
    fans: number
    /** The number of turns for the race. */
    turnNumber: number
    /** The formatted name of the race. */
    nameFormatted: string
}

export interface DatabaseSkill {
    /** The unique identifier for the skill. */
    id: number
    /** The key of the skill. */
    key: string
    /** The skill ID. */
    skill_id: number
    /** The skill ID for the inherited version of the skill. Same as ID if skill can't be inherited. */
    gene_id: number
    /** The name of the skill. */
    name_en: string
    /** The description of the skill. */
    desc_en: string
    /** The icon ID of the skill. */
    icon_id: number
    /** The cost of the skill. */
    cost: number
    /** The evaluation point of the skill. */
    eval_pt: number
    /** The point ratio of the skill. */
    pt_ratio: number
    /** The rarity of the skill. */
    rarity: number
    /** The condition of the skill. */
    condition: string
    /** The precondition of the skill. */
    precondition: string
    /** Whether the skill is inherited. */
    inherited: boolean
    /** The community tier of the skill. */
    community_tier: number | null
    /** The versions of the skill. */
    versions: number[] | null
    /** The upgrade of the skill. */
    upgrade: number | null
    /** The downgrade of the skill. */
    downgrade: number | null
}

export interface DatabaseProfile {
    /** The unique identifier for the profile. */
    id: number
    /** The name of the profile. */
    name: string
    /** The settings of the profile. */
    settings: string
    /** The timestamp of when the profile was created. */
    created_at: string
    /** The timestamp of when the profile was last updated. */
    updated_at: string
}

/**
 * Database utility class for managing settings persistence with `SQLite`.
 * Stores settings as key-value pairs organized by category for efficient querying.
 */
export class DatabaseManager {
    private DATABASE_NAME = "settings.db"
    private STRING_ONLY_SETTINGS = ["racingPlan", "racingPlanData", "discordToken", "discordUserID"]
    private TABLE_SETTINGS = "settings"
    private TABLE_RACES = "races"
    private TABLE_SKILLS = "skills"
    private TABLE_PROFILES = "profiles"

    private db: SQLite.SQLiteDatabase | null = null
    private isInitializing = false
    private initializationPromise: Promise<void> | null = null
    private isTransactionActive = false
    private transactionQueue: Array<() => Promise<void>> = []

    /**
     * Serialize a value to a string for storage.
     * @param value - The value to serialize.
     * @returns The serialized string value.
     */
    private serializeValue(value: any): string {
        return typeof value === "string" ? value : JSON.stringify(value)
    }

    /**
     * Deserialize a value from a string, handling string-only settings.
     * @param key - The setting key to check if it should remain as a string.
     * @param value - The string value to deserialize.
     * @returns The deserialized value.
     */
    private deserializeValue(key: string, value: string): any {
        if (this.STRING_ONLY_SETTINGS.includes(key)) {
            return value
        }
        try {
            return JSON.parse(value)
        } catch {
            return value
        }
    }

    // ============================================================================
    // Initialization and Migration Methods
    // ============================================================================

    /**
     * Ensure the database is initialized, throwing an error if not.
     * @throws Error if database is not initialized.
     */
    private ensureInitialized(): void {
        if (!this.db) {
            throw new Error("Database not initialized")
        }
    }

    /**
     * Initialize the database and create tables if they don't exist.
     * @returns A promise that resolves when the database is initialized.
     */
    async initialize(): Promise<void> {
        const endTiming = startTiming("database_initialize", "database")

        // If already initializing, wait for the existing initialization to complete.
        if (this.isInitializing && this.initializationPromise) {
            logWithTimestamp("Database initialization already in progress, waiting...")
            endTiming({ status: "already_initializing" })
            return this.initializationPromise
        }

        // If already initialized, return immediately.
        if (this.db) {
            logWithTimestamp("Database already initialized, skipping...")
            endTiming({ status: "already_initialized" })
            return
        }

        this.isInitializing = true
        this.initializationPromise = this._performInitialization()

        try {
            await this.initializationPromise
            endTiming({ status: "success" })
        } finally {
            this.isInitializing = false
            this.initializationPromise = null
        }
    }

    /**
     * Perform the database initialization.
     * @returns A promise that resolves when the database is initialized.
     */
    private async _performInitialization(): Promise<void> {
        try {
            logWithTimestamp("Starting database initialization...")
            this.db = await SQLite.openDatabaseAsync(this.DATABASE_NAME, {
                useNewConnection: true,
            })
            logWithTimestamp("Database opened successfully")

            if (!this.db) {
                throw new Error("Database object is null after opening")
            }

            // Create settings table.
            logWithTimestamp("Creating settings table...")
            await this.db.execAsync(`
                CREATE TABLE IF NOT EXISTS ${this.TABLE_SETTINGS} (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    category TEXT NOT NULL,
                    key TEXT NOT NULL,
                    value TEXT NOT NULL,
                    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                    UNIQUE(category, key)
                )
            `)
            logWithTimestamp("Settings table created successfully.")

            // Create races table.
            logWithTimestamp("Creating races table...")
            await this.db.execAsync(`
                CREATE TABLE IF NOT EXISTS ${this.TABLE_RACES} (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    key TEXT UNIQUE NOT NULL,
                    name TEXT NOT NULL,
                    date TEXT NOT NULL,
                    raceTrack TEXT NOT NULL,
                    course TEXT,
                    direction TEXT NOT NULL,
                    grade TEXT NOT NULL,
                    terrain TEXT NOT NULL,
                    distanceType TEXT NOT NULL,
                    distanceMeters INTEGER NOT NULL,
                    fans INTEGER NOT NULL,
                    turnNumber INTEGER NOT NULL,
                    nameFormatted TEXT NOT NULL
                )
            `)
            logWithTimestamp("Races table created successfully.")

            // Create skills table.
            logWithTimestamp("Creating skills table...")
            await this.db.execAsync(`
                DROP TABLE IF EXISTS ${this.TABLE_SKILLS};
                CREATE TABLE ${this.TABLE_SKILLS} (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    key TEXT UNIQUE NOT NULL,
                    skill_id INTEGER NOT NULL,
                    gene_id INTEGER NOT NULL,
                    name_en TEXT NOT NULL,
                    desc_en TEXT NOT NULL,
                    icon_id INTEGER NOT NULL,
                    cost INTEGER NOT NULL,
                    eval_pt INTEGER NOT NULL,
                    pt_ratio REAL NOT NULL,
                    rarity INTEGER NOT NULL,
                    condition TEXT NOT NULL,
                    precondition TEXT NOT NULL,
                    inherited BOOLEAN NOT NULL DEFAULT 0,
                    community_tier INTEGER,
                    versions TEXT NOT NULL,
                    upgrade INTEGER,
                    downgrade INTEGER
                )
            `)
            logWithTimestamp("Skills table created successfully.")

            // Create profiles table.
            logWithTimestamp("Creating profiles table...")
            await this.db.execAsync(`
                CREATE TABLE IF NOT EXISTS ${this.TABLE_PROFILES} (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    name TEXT UNIQUE NOT NULL,
                    settings TEXT NOT NULL,
                    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP
                )
            `)
            logWithTimestamp("Profiles table created successfully.")

            // Migrate existing profiles from old to new schema.
            await this.migrateProfilesSchema()

            // Create indexes for faster queries.
            logWithTimestamp("Creating indexes...")
            await this.db.execAsync(`
                CREATE INDEX IF NOT EXISTS idx_settings_category_key 
                ON ${this.TABLE_SETTINGS}(category, key)
            `)
            await this.db.execAsync(`
                CREATE INDEX IF NOT EXISTS idx_races_turn_number 
                ON ${this.TABLE_RACES}(turnNumber)
            `)
            await this.db.execAsync(`
                CREATE INDEX IF NOT EXISTS idx_races_name_formatted 
                ON ${this.TABLE_RACES}(nameFormatted)
            `)
            await this.db.execAsync(`
                CREATE INDEX IF NOT EXISTS idx_skills_name_en 
                ON ${this.TABLE_SKILLS}(name_en)
            `)
            await this.db.execAsync(`
                CREATE INDEX IF NOT EXISTS idx_profiles_name 
                ON ${this.TABLE_PROFILES}(name)
            `)
            logWithTimestamp("Indexes created successfully.")

            logWithTimestamp("Database initialized successfully.")
        } catch (error) {
            logErrorWithTimestamp("Failed to initialize database:", error)
            this.db = null // Reset database on error.
            throw error
        }
    }

    /**
     * Migrate profiles table schema from old to new format (settings JSON).
     * @returns A promise that resolves when the profiles table is migrated.
     */
    private async migrateProfilesSchema(): Promise<void> {
        if (!this.db) {
            return
        }

        try {
            const tableInfo = await this.db.getAllAsync<{ name: string; type: string }>(`PRAGMA table_info(${this.TABLE_PROFILES})`)
            const hasTrainingSettings = tableInfo.some((col) => col.name === "training_settings")
            const hasTrainingStatTarget = tableInfo.some((col) => col.name === "trainingStatTarget_settings")
            const hasSettings = tableInfo.some((col) => col.name === "settings")

            // If old columns exist and new column doesn't, migrate.
            if ((hasTrainingSettings || hasTrainingStatTarget) && !hasSettings) {
                logWithTimestamp("[DB] Migrating profiles table to new settings format...")

                // Create new table with settings column.
                await this.createProfilesMigrationTable()

                // Migrate existing data: combine training and training stat target settings into a single settings JSON.
                await this.migrateProfilesData(hasTrainingSettings, hasTrainingStatTarget)

                // Complete the migration by replacing the old table.
                await this.completeProfilesMigration()

                logWithTimestamp("[DB] Successfully migrated profiles table to new settings format.")
            }
        } catch (error) {
            // Allow app to continue even if migration fails.
            logErrorWithTimestamp("[DB] Failed to migrate profiles table:", error)
        }
    }

    /**
     * Create the new profiles table for migration.
     * @returns A promise that resolves when the new profiles table is created.
     */
    private async createProfilesMigrationTable(): Promise<void> {
        if (!this.db) {
            return
        }
        await this.db.execAsync(`
            CREATE TABLE IF NOT EXISTS ${this.TABLE_PROFILES}_new (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT UNIQUE NOT NULL,
                settings TEXT NOT NULL,
                created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                updated_at DATETIME DEFAULT CURRENT_TIMESTAMP
            )
        `)
    }

    /**
     * Migrate profiles data from old schema to new schema.
     * @param hasTrainingSettings - Whether the `training_settings` column exists.
     * @param hasTrainingStatTarget - Whether the `trainingStatTarget_settings` column exists.
     * @returns A promise that resolves when the profiles are migrated.
     */
    private async migrateProfilesData(hasTrainingSettings: boolean, hasTrainingStatTarget: boolean): Promise<void> {
        if (!this.db) {
            return
        }

        // Build json_object() dynamically based on which columns exist.
        const jsonObjectParts: string[] = []
        if (hasTrainingSettings) {
            jsonObjectParts.push("'training', json(training_settings)")
        }
        if (hasTrainingStatTarget) {
            jsonObjectParts.push("'trainingStatTarget', json(trainingStatTarget_settings)")
        }

        const jsonObjectSql = `json_object(${jsonObjectParts.join(", ")})`

        await this.db.execAsync(`
            INSERT INTO ${this.TABLE_PROFILES}_new (id, name, settings, created_at, updated_at)
            SELECT 
                id, 
                name, 
                ${jsonObjectSql} as settings,
                created_at, 
                updated_at
            FROM ${this.TABLE_PROFILES}
        `)
    }

    /**
     * Complete the profiles migration by replacing the old table with the new one.
     * @returns A promise that resolves when the migration is completed.
     */
    private async completeProfilesMigration(): Promise<void> {
        if (!this.db) {
            return
        }
        await this.db.execAsync(`DROP TABLE ${this.TABLE_PROFILES}`)
        await this.db.execAsync(`ALTER TABLE ${this.TABLE_PROFILES}_new RENAME TO ${this.TABLE_PROFILES}`)
        await this.db.execAsync(`
            CREATE INDEX IF NOT EXISTS idx_profiles_name 
            ON ${this.TABLE_PROFILES}(name)
        `)
    }

    // ============================================================================
    // Settings Methods
    // ============================================================================

    /**
     * Save settings to database by category and key.
     * @param category - The category of the setting to save.
     * @param key - The key of the setting to save.
     * @param value - The value of the setting to save.
     * @param suppressLogging - Whether to suppress logging of the setting being saved.
     * @returns A promise that resolves when the setting is saved.
     */
    async saveSetting(category: string, key: string, value: any, suppressLogging: boolean = false): Promise<void> {
        const endTiming = startTiming("database_save_setting", "database")

        this.ensureInitialized()

        try {
            const valueString = this.serializeValue(value)
            if (!suppressLogging) {
                logWithTimestamp(`[DB] Saving setting: ${category}.${key} = ${valueString.substring(0, 100)}...`)
            }
            await this.db!.runAsync(
                `INSERT OR REPLACE INTO ${this.TABLE_SETTINGS} (category, key, value, updated_at) 
                 VALUES (?, ?, ?, CURRENT_TIMESTAMP)`,
                [category, key, valueString]
            )
            if (!suppressLogging) {
                logWithTimestamp(`[DB] Successfully saved setting: ${category}.${key}`)
            }
            endTiming({ status: "success", category, key })
        } catch (error) {
            logErrorWithTimestamp(`[DB] Failed to save setting ${category}.${key}:`, error)
            endTiming({ status: "error", category, key, error: error instanceof Error ? error.message : String(error) })
            throw error
        }
    }

    // ============================================================================
    // Utility Methods
    // ============================================================================

    /**
     * Execute a database operation with queue management to prevent concurrent operations.
     * Note: This is a queue system, not `SQLite` transactions. `SQLite` transactions are handled within the operations.
     * @param operation - The operation to execute.
     * @returns A promise that resolves when the operation is executed.
     */
    private async executeWithQueue<T>(operation: () => Promise<T>): Promise<T> {
        return new Promise((resolve, reject) => {
            const executeOperation = async () => {
                if (this.isTransactionActive) {
                    // If a transaction is already active, queue this operation.
                    this.transactionQueue.push(executeOperation)
                    return
                }

                this.isTransactionActive = true

                try {
                    const result = await operation()
                    resolve(result)
                } catch (error) {
                    // Clear the transaction queue on error to prevent cascading failures.
                    this.clearTransactionQueue()
                    reject(error)
                } finally {
                    this.isTransactionActive = false

                    // Process the next queued operation if any.
                    if (this.transactionQueue.length > 0) {
                        const nextOperation = this.transactionQueue.shift()
                        if (nextOperation) {
                            // Use setTimeout to avoid stack overflow with recursive calls.
                            setTimeout(() => nextOperation(), 0)
                        }
                    }
                }
            }

            executeOperation()
        })
    }

    /**
     * Save multiple settings in a single transaction for better performance.
     * @param settings - The settings to save.
     * @returns A promise that resolves when the settings are saved.
     */
    async saveSettingsBatch(settings: Array<{ category: string; key: string; value: any }>): Promise<void> {
        const endTiming = startTiming("database_save_settings_batch", "database")

        this.ensureInitialized()

        if (settings.length === 0) {
            endTiming({ status: "skipped", reason: "no_settings" })
            return
        }

        try {
            await this.executeWithQueue(async () => {
                logWithTimestamp(`[DB] Saving ${settings.length} settings in batch.`)

                await this.db!.runAsync("BEGIN TRANSACTION")
                const stmt = await this.db!.prepareAsync(
                    `INSERT OR REPLACE INTO ${this.TABLE_SETTINGS} (category, key, value, updated_at) 
                     VALUES (?, ?, ?, CURRENT_TIMESTAMP)`
                )

                // Execute all settings in batch.
                for (const setting of settings) {
                    const valueString = this.serializeValue(setting.value)
                    await stmt.executeAsync([setting.category, setting.key, valueString])
                }

                // Finalize statement and commit transaction.
                await stmt.finalizeAsync()
                await this.db!.runAsync("COMMIT")

                logWithTimestamp(`[DB] Successfully saved ${settings.length} settings in batch.`)
            })

            endTiming({ status: "success", settingsCount: settings.length })
        } catch (error) {
            const settingsInfo = settings.length > 0 ? ` (${settings.length} settings: ${settings.map((s) => `${s.category}.${s.key}`).join(", ")})` : " (no settings)"
            logErrorWithTimestamp(`[DB] Failed to save settings batch${settingsInfo}:`, error)

            // Rollback transaction on error.
            try {
                if (this.db && this.isTransactionActive) {
                    await this.db.runAsync("ROLLBACK")
                }
            } catch (rollbackError) {
                logErrorWithTimestamp(`[DB] Failed to rollback transaction${settingsInfo}:`, rollbackError)
            }

            endTiming({ status: "error", settingsCount: settings.length, error: error instanceof Error ? error.message : String(error) })
            throw error
        }
    }

    /**
     * Load a specific setting from database.
     * @param category - The category of the setting to load.
     * @param key - The key of the setting to load.
     * @returns The value of the setting, or null if not found.
     */
    async loadSetting(category: string, key: string): Promise<any> {
        const endTiming = startTiming("database_load_setting", "database")
        this.ensureInitialized()

        try {
            const result = await this.db!.getFirstAsync<DatabaseSettings>(`SELECT * FROM ${this.TABLE_SETTINGS} WHERE category = ? AND key = ?`, [category, key])

            if (!result) {
                endTiming({ status: "not_found", category, key })
                return null
            }

            const value = this.deserializeValue(key, result.value)
            endTiming({ status: "success", category, key })
            return value
        } catch (error) {
            logErrorWithTimestamp(`[DB] Failed to load setting ${category}.${key}:`, error)
            endTiming({ status: "error", category, key, error: error instanceof Error ? error.message : String(error) })
            throw error
        }
    }

    /**
     * Load all settings from database.
     * @returns A promise that resolves with a record of settings organized by category and key.
     */
    async loadAllSettings(): Promise<Record<string, Record<string, any>>> {
        const endTiming = startTiming("database_load_all_settings", "database")

        this.ensureInitialized()

        try {
            const results = await this.db!.getAllAsync<DatabaseSettings>(`SELECT * FROM ${this.TABLE_SETTINGS} ORDER BY category, key`)

            const settings: Record<string, Record<string, any>> = {}
            for (const result of results) {
                if (!settings[result.category]) {
                    settings[result.category] = {}
                }
                settings[result.category][result.key] = this.deserializeValue(result.key, result.value)
            }

            endTiming({ status: "success", totalSettings: results.length, categoriesCount: Object.keys(settings).length })
            return settings
        } catch (error) {
            logErrorWithTimestamp("[DB] Failed to load all settings:", error)
            endTiming({ status: "error", error: error instanceof Error ? error.message : String(error) })
            throw error
        }
    }

    // ============================================================================
    // Races Methods
    // ============================================================================

    /**
     * Save multiple races using prepared statements for better performance and security.
     * @param races - The races to save.
     * @returns A promise that resolves when the races are saved.
     */
    async saveRacesBatch(races: Array<Omit<DatabaseRace, "id">>): Promise<void> {
        const endTiming = startTiming("database_save_races_batch", "database")

        this.ensureInitialized()

        if (races.length === 0) {
            endTiming({ status: "skipped", reason: "no_races" })
            return
        }

        try {
            await this.executeWithQueue(async () => {
                logWithTimestamp(`[DB] Saving ${races.length} races using prepared statement.`)

                await this.db!.runAsync("BEGIN TRANSACTION")
                const stmt = await this.db!.prepareAsync(
                    `INSERT OR REPLACE INTO ${this.TABLE_RACES} (key, name, date, raceTrack, course, direction, grade, terrain, distanceType, distanceMeters, fans, turnNumber, nameFormatted) 
                     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`
                )

                // Execute all races in batch using prepared statement.
                for (const race of races) {
                    await stmt.executeAsync([
                        race.key,
                        race.name,
                        race.date,
                        race.raceTrack,
                        race.course,
                        race.direction,
                        race.grade,
                        race.terrain,
                        race.distanceType,
                        race.distanceMeters,
                        race.fans,
                        race.turnNumber,
                        race.nameFormatted,
                    ])
                }

                // Finalize statement and commit transaction.
                await stmt.finalizeAsync()
                await this.db!.runAsync("COMMIT")

                logWithTimestamp(`[DB] Successfully saved ${races.length} races in batch.`)
            })

            endTiming({ status: "success", racesCount: races.length })
        } catch (error) {
            const racesInfo = races.length > 0 ? ` (${races.length} races: ${races.map((r) => `${r.name} (turn ${r.turnNumber})`).join(", ")})` : " (no races)"
            logErrorWithTimestamp(`[DB] Failed to save races batch${racesInfo}:`, error)

            // Rollback transaction on error.
            try {
                if (this.db && this.isTransactionActive) {
                    await this.db.runAsync("ROLLBACK")
                }
            } catch (rollbackError) {
                logErrorWithTimestamp(`[DB] Failed to rollback transaction${racesInfo}:`, rollbackError)
            }

            endTiming({ status: "error", racesCount: races.length, error: error instanceof Error ? error.message : String(error) })
            throw error
        }
    }

    /**
     * Clear all races from the database.
     * @returns A promise that resolves when the races are cleared.
     */
    async clearRaces(): Promise<void> {
        const endTiming = startTiming("database_clear_races", "database")

        this.ensureInitialized()

        try {
            await this.db!.runAsync(`DELETE FROM ${this.TABLE_RACES}`)
            logWithTimestamp("[DB] Successfully cleared all races.")
            endTiming({ status: "success" })
        } catch (error) {
            logErrorWithTimestamp("[DB] Failed to clear races:", error)
            endTiming({ status: "error", error: error instanceof Error ? error.message : String(error) })
            throw error
        }
    }

    // ============================================================================
    // Skills Methods
    // ============================================================================

    /**
     * Save multiple skills using prepared statements for better performance and security.
     * @param skills - The skills to save.
     * @returns A promise that resolves when the skills are saved.
     */
    async saveSkillsBatch(skills: Array<Omit<DatabaseSkill, "id">>): Promise<void> {
        const endTiming = startTiming("database_save_skills_batch", "database")

        this.ensureInitialized()

        if (skills.length === 0) {
            endTiming({ status: "skipped", reason: "no_skills" })
            return
        }

        try {
            await this.executeWithQueue(async () => {
                logWithTimestamp(`[DB] Saving ${skills.length} skills using prepared statement.`)

                await this.db!.runAsync("BEGIN TRANSACTION")
                const stmt = await this.db!.prepareAsync(
                    `INSERT OR REPLACE INTO ${this.TABLE_SKILLS} (key, skill_id, gene_id, name_en, desc_en, icon_id, cost, eval_pt, pt_ratio, rarity, condition, precondition, inherited, community_tier, versions, upgrade, downgrade)
                     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`
                )

                // Execute all skills in batch using prepared statement.
                for (const skill of skills) {
                    let versions: string = ""
                    if (skill.versions !== null) {
                        versions = skill.versions.join(",")
                    }
                    await stmt.executeAsync([
                        skill.key,
                        skill.skill_id,
                        skill.gene_id,
                        skill.name_en,
                        skill.desc_en,
                        skill.icon_id,
                        skill.cost,
                        skill.eval_pt,
                        skill.pt_ratio,
                        skill.rarity,
                        skill.condition,
                        skill.precondition,
                        skill.inherited,
                        skill.community_tier,
                        versions,
                        skill.upgrade,
                        skill.downgrade,
                    ])
                }

                // Finalize statement and commit transaction.
                await stmt.finalizeAsync()
                await this.db!.runAsync("COMMIT")

                logWithTimestamp(`[DB] Successfully saved ${skills.length} skills in batch.`)
            })

            endTiming({ status: "success", skillsCount: skills.length })
        } catch (error) {
            const skillsInfo = skills.length > 0 ? ` (${skills.length} skills: ${skills.map((s) => `${s.name_en} (id ${s.skill_id})`).join(", ")})` : " (no skills)"
            logErrorWithTimestamp(`[DB] Failed to save skills batch${skillsInfo}:\n`, error)

            // Rollback transaction on error.
            try {
                if (this.db && this.isTransactionActive) {
                    await this.db.runAsync("ROLLBACK")
                }
            } catch (rollbackError) {
                logErrorWithTimestamp(`[DB] Failed to rollback transaction${skillsInfo}:`, rollbackError)
            }

            endTiming({ status: "error", skillsCount: skills.length, error: error instanceof Error ? error.message : String(error) })
            throw error
        }
    }

    /**
     * Clear all skills from the database.
     * @returns A promise that resolves when the skills are cleared.
     */
    async clearSkills(): Promise<void> {
        const endTiming = startTiming("database_clear_skills", "database")

        this.ensureInitialized()

        try {
            await this.db!.runAsync(`DELETE FROM ${this.TABLE_SKILLS}`)
            logWithTimestamp("[DB] Successfully cleared all skills.")
            endTiming({ status: "success" })
        } catch (error) {
            logErrorWithTimestamp("[DB] Failed to clear skills:", error)
            endTiming({ status: "error", error: error instanceof Error ? error.message : String(error) })
            throw error
        }
    }

    /**
     * Check if the database is properly initialized.
     * @returns True if the database is initialized, false otherwise.
     */
    isInitialized(): boolean {
        return this.db !== null
    }

    /**
     * Clear the transaction queue and reset transaction state (for error recovery).
     */
    private clearTransactionQueue(): void {
        this.transactionQueue = []
        this.isTransactionActive = false
    }

    // ============================================================================
    // Profiles Methods
    // ============================================================================

    /**
     * Get all profiles from the database.
     * @returns A promise that resolves with an array of all profiles.
     */
    async getAllProfiles(): Promise<DatabaseProfile[]> {
        const endTiming = startTiming("database_get_all_profiles", "database")

        this.ensureInitialized()

        try {
            const results = await this.db!.getAllAsync<DatabaseProfile>(`SELECT * FROM ${this.TABLE_PROFILES} ORDER BY name`)
            endTiming({ status: "success", totalProfiles: results.length })
            return results
        } catch (error) {
            logErrorWithTimestamp("[DB] Failed to load all profiles:", error)
            endTiming({ status: "error", error: error instanceof Error ? error.message : String(error) })
            throw error
        }
    }

    /**
     * Get a single profile by ID.
     * @param id - The ID of the profile to load.
     * @returns A promise that resolves with the profile of the given ID, or null if not found.
     */
    async getProfile(id: number): Promise<DatabaseProfile | null> {
        const endTiming = startTiming("database_get_profile", "database")

        this.ensureInitialized()

        try {
            const result = await this.db!.getFirstAsync<DatabaseProfile>(`SELECT * FROM ${this.TABLE_PROFILES} WHERE id = ?`, [id])
            endTiming({ status: "success", found: !!result })
            return result || null
        } catch (error) {
            logErrorWithTimestamp(`[DB] Failed to load profile ${id}:`, error)
            endTiming({ status: "error", error: error instanceof Error ? error.message : String(error) })
            throw error
        }
    }

    /**
     * Save a profile (create or update).
     * @param profile - The profile to save.
     * @returns A promise that resolves with the ID of the saved profile.
     */
    async saveProfile(profile: { id?: number; name: string; settings: any }): Promise<number> {
        const endTiming = startTiming("database_save_profile", "database")

        this.ensureInitialized()

        try {
            const settingsJson = JSON.stringify(profile.settings)

            if (profile.id) {
                // Update existing profile.
                logWithTimestamp(`[DB] Updating profile: ${profile.name} (id: ${profile.id})`)
                try {
                    await this.db!.runAsync(
                        `UPDATE ${this.TABLE_PROFILES} 
                         SET name = ?, settings = ?, updated_at = CURRENT_TIMESTAMP 
                         WHERE id = ?`,
                        [profile.name, settingsJson, profile.id]
                    )
                    logWithTimestamp(`[DB] Successfully updated profile: ${profile.name}`)
                    endTiming({ status: "success", profileId: profile.id, isUpdate: true })
                    return profile.id
                } catch (updateError: any) {
                    // If UNIQUE constraint error and name hasn't changed, check if it's the same profile.
                    if (updateError?.message?.includes("UNIQUE constraint")) {
                        // Check if this profile already has this name (name wasn't actually changed).
                        const existingProfile = await this.getProfile(profile.id)
                        if (existingProfile && existingProfile.name === profile.name) {
                            // Name is the same, just update settings.
                            await this.db!.runAsync(
                                `UPDATE ${this.TABLE_PROFILES} 
                                 SET settings = ?, updated_at = CURRENT_TIMESTAMP 
                                 WHERE id = ?`,
                                [settingsJson, profile.id]
                            )
                            logWithTimestamp(`[DB] Successfully updated profile settings: ${profile.name}`)
                            endTiming({ status: "success", profileId: profile.id, isUpdate: true })
                            return profile.id
                        }
                    }
                    throw updateError
                }
            } else {
                // Create new profile.
                logWithTimestamp(`[DB] Creating profile: ${profile.name}`)
                const result = await this.db!.runAsync(
                    `INSERT INTO ${this.TABLE_PROFILES} (name, settings, created_at, updated_at) 
                     VALUES (?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)`,
                    [profile.name, settingsJson]
                )
                const profileId = result.lastInsertRowId
                logWithTimestamp(`[DB] Successfully created profile: ${profile.name} (id: ${profileId})`)
                endTiming({ status: "success", profileId, isUpdate: false })
                return profileId
            }
        } catch (error) {
            logErrorWithTimestamp(`[DB] Failed to save profile ${profile.name}:`, error)
            endTiming({ status: "error", profileName: profile.name, error: error instanceof Error ? error.message : String(error) })
            throw error
        }
    }

    /**
     * Delete a profile by ID.
     * @param id - The ID of the profile to delete.
     * @returns A promise that resolves when the profile is deleted.
     */
    async deleteProfile(id: number): Promise<void> {
        const endTiming = startTiming("database_delete_profile", "database")

        this.ensureInitialized()

        try {
            logWithTimestamp(`[DB] Deleting profile with id: ${id}`)
            await this.db!.runAsync(`DELETE FROM ${this.TABLE_PROFILES} WHERE id = ?`, [id])
            logWithTimestamp(`[DB] Successfully deleted profile with id: ${id}`)
            endTiming({ status: "success", profileId: id })
        } catch (error) {
            logErrorWithTimestamp(`[DB] Failed to delete profile ${id}:`, error)
            endTiming({ status: "error", profileId: id, error: error instanceof Error ? error.message : String(error) })
            throw error
        }
    }

    /**
     * Get the current active profile name from settings.
     * @returns A promise that resolves with the current active profile name, or null if no profile is active.
     */
    async getCurrentProfileName(): Promise<string | null> {
        const endTiming = startTiming("database_get_current_profile_name", "database")
        this.ensureInitialized()

        try {
            const profileName = await this.loadSetting("misc", "currentProfileName")
            endTiming({ status: "success", profileName })
            return profileName || null
        } catch (error) {
            logErrorWithTimestamp("[DB] Failed to load current profile name:", error)
            endTiming({ status: "error", error: error instanceof Error ? error.message : String(error) })
            return null
        }
    }

    /**
     * Set the current active profile name in settings.
     * @param profileName - The name of the profile to set as active.
     * @returns A promise that resolves when the current active profile name is set or null if no profile is active.
     */
    async setCurrentProfileName(profileName: string | null): Promise<void> {
        const endTiming = startTiming("database_set_current_profile_name", "database")
        this.ensureInitialized()

        try {
            if (profileName) {
                await this.saveSetting("misc", "currentProfileName", profileName, true)
            } else {
                // Delete the setting if profileName is null.
                await this.db!.runAsync(`DELETE FROM ${this.TABLE_SETTINGS} WHERE category = ? AND key = ?`, ["misc", "currentProfileName"])
            }
            endTiming({ status: "success", profileName })
        } catch (error) {
            logErrorWithTimestamp("[DB] Failed to save current profile name:", error)
            endTiming({ status: "error", error: error instanceof Error ? error.message : String(error) })
            throw error
        }
    }
}

// Available as a singleton instance.
export const databaseManager = new DatabaseManager()
