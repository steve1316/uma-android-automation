/** A legend in the Daily Legend Races grid. */
export interface DailyLegend {
    /** Legend name as shown on the card, also used to find its logo template. */
    name: string
    /** The race shown under the card. */
    race: string
}

/** Daily Race options. */
export const DAILY_RACE_TYPES = ["Moonlight Sho", "Jupiter Cup"] as const

/** What each Daily Race awards, for the picker labels. */
export const DAILY_RACE_REWARDS: Record<(typeof DAILY_RACE_TYPES)[number], string> = {
    "Jupiter Cup": "Support Points",
    "Moonlight Sho": "Monies",
}

/** Legends in grid order, three per row. */
export const LEGENDS: DailyLegend[] = [
    { name: "El Condor Pasa", race: "Japan Cup" },
    { name: "Special Week", race: "Japan Cup" },
    { name: "Symboli Rudolf", race: "Japan Cup" },
    { name: "King Halo", race: "Sprinters S." },
    { name: "Taiki Shuttle", race: "Sprinters S." },
    { name: "Sakura Bakushin O", race: "Sprinters S." },
    { name: "Curren Chan", race: "Sprinters S." },
    { name: "Winning Ticket", race: "Japanese Derby" },
    { name: "Vodka", race: "Japanese Derby" },
    { name: "Tokai Teio", race: "Japanese Derby" },
    { name: "Mayano Top Gun", race: "Tenno Sho (Spring)" },
    { name: "Mejiro McQueen", race: "Tenno Sho (Spring)" },
    { name: "T.M. Opera O", race: "Tenno Sho (Spring)" },
    { name: "Super Creek", race: "Tenno Sho (Spring)" },
    { name: "Mejiro Ryan", race: "Takarazuka Kinen" },
    { name: "Silence Suzuka", race: "Takarazuka Kinen" },
    { name: "Gold Ship", race: "Takarazuka Kinen" },
    { name: "Maruzensky", race: "Asahi Hai F.S." },
    { name: "Fuji Kiseki", race: "Asahi Hai F.S." },
    { name: "Mihono Bourbon", race: "Asahi Hai F.S." },
    { name: "Matikanefukukitaru", race: "Kikuka Sho" },
    { name: "Seiun Sky", race: "Kikuka Sho" },
    { name: "Biwa Hayahide", race: "Kikuka Sho" },
    { name: "Air Groove", race: "Tenno Sho (Autumn)" },
    { name: "Eishin Flash", race: "Tenno Sho (Autumn)" },
    { name: "Agnes Digital", race: "Tenno Sho (Autumn)" },
    { name: "Nice Nature", race: "Arima Kinen" },
    { name: "Grass Wonder", race: "Arima Kinen" },
    { name: "Oguri Cap", race: "Arima Kinen" },
]

/** Shoes that can be requested in the Club. */
export const SHOES: string[] = ["Sprint Racing Shoes", "Mile Racing Shoes", "Medium Racing Shoes", "Long Racing Shoes", "Dirt Racing Shoes"]

/** Winner's Sashes that can be requested in the Club, in the game's list order. */
export const SASHES: string[] = [
    "February S.",
    "Takamatsunomiya Kinen",
    "Osaka Hai",
    "Oka Sho",
    "Satsuki Sho",
    "Tenno Sho (Spring)",
    "NHK Mile C.",
    "Victoria Mile",
    "Japanese Oaks",
    "Japanese Derby",
    "Yasuda Kinen",
    "Takarazuka Kinen",
    "Sprinters S.",
    "Shuka Sho",
    "Kikuka Sho",
    "Tenno Sho (Autumn)",
    "Mile Ch.",
    "Queen Elizabeth II Cup",
    "Japan Cup",
    "Champions Cup",
    "Hanshin J.F.",
    "Asahi Hai F.S.",
    "Arima Kinen",
    "Hopeful S.",
    "Japan Dirt Derby",
    "JBC Sprint",
    "JBC Classic",
    "JBC Ladies' Classic",
    "Teio Sho",
    "Tokyo Daishoten",
    "Kawasaki Kinen",
    "Zen-Nippon Junior Yushun",
    "Kashiwa Kinen",
    "M.C. Nambu Hai",
].map((race) => `${race} Winner's Sash`)
