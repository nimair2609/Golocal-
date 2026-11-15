package com.example.domain

data class WestBengalPlace(
    val id: String,
    val nameEn: String,
    val nameBn: String,
    val district: String,
    val block: String,
    val category: String, // railway_station, bus_stand, airport, village, hospital, school, market, town
    val lat: Double,
    val lng: Double
)

object WestBengalCatalog {
    val places: List<WestBengalPlace> = listOf(
        WestBengalPlace("bandel_jn", "Bandel Junction Railway Station", "ব্যান্ডেল জংশন রেল স্টেশন", "Hooghly", "Chinsurah-Mogra", "railway_station", 22.9238, 88.3788),
        WestBengalPlace("chinsurah_ma", "Chinsurah Gharimore Market", "চুঁচুড়া ঘড়িমোড় বাজার", "Hooghly", "Chinsurah-Mogra", "market", 22.8902, 88.3937),
        WestBengalPlace("imambara_hosp", "Hooghly Imambara District Hospital", "ইমামবাড়া জেলা হাসপাতাল", "Hooghly", "Chinsurah-Mogra", "hospital", 22.9041, 88.3976),
        WestBengalPlace("mogra_village", "Mogra Gram Panchayat & Bazaar", "মগরা গ্রাম ও বাজার", "Hooghly", "Chinsurah-Mogra", "village", 22.9873, 88.3821),
        WestBengalPlace("tarakeswar_stn", "Tarakeswar Railway Station", "তারকেশ্বর রেল স্টেশন", "Hooghly", "Tarakeswar", "railway_station", 22.8881, 88.0155),
        WestBengalPlace("arambagh_bus", "Arambagh Central Bus Stand", "আরামবাগ বাস স্ট্যান্ড", "Hooghly", "Arambagh", "bus_stand", 22.8832, 87.7834),
        WestBengalPlace("howrah_jn", "Howrah Junction Railway Station", "হাওড়া জংশন স্টেশন", "Howrah", "Howrah Sadar", "railway_station", 22.5839, 88.3426),
        WestBengalPlace("sealdah_stn", "Sealdah Railway Station", "শিয়ালদহ রেল স্টেশন", "Kolkata", "Central Kolkata", "railway_station", 22.5647, 88.3708),
        WestBengalPlace("esplanade_bus", "Esplanade Central Bus Terminus", "এসপ্ল্যানেড বাস টার্মিনাস", "Kolkata", "Central Kolkata", "bus_stand", 22.5653, 88.3522),
        WestBengalPlace("kolkata_ccu_airport", "Netaji Subhas Chandra Bose Airport (CCU)", "কলকাতা আন্তর্জাতিক বিমানবন্দর", "North 24 Parganas", "Dum Dum", "airport", 22.6547, 88.4467),
        WestBengalPlace("barddhaman_jn", "Barddhaman Junction Railway Station", "বর্ধমান জংশন স্টেশন", "Purba Bardhaman", "Bardhaman I", "railway_station", 23.2504, 87.8705),
        WestBengalPlace("burdwan_med", "Burdwan Medical College & Hospital", "বর্ধমান মেডিকেল কলেজ", "Purba Bardhaman", "Bardhaman I", "hospital", 23.2464, 87.8548),
        WestBengalPlace("katwa_jn", "Katwa Junction Railway Station", "কাটোয়া জংশন স্টেশন", "Purba Bardhaman", "Katwa I", "railway_station", 23.6431, 88.1303),
        WestBengalPlace("bolpur_stn", "Bolpur Shantiniketan Station", "বোলপুর শান্তিনিকেতন স্টেশন", "Birbhum", "Bolpur-Sriniketan", "railway_station", 23.6683, 87.7018),
        WestBengalPlace("visva_bharati", "Visva-Bharati University Campus", "বিশ্বভারতী বিশ্ববিদ্যালয়", "Birbhum", "Bolpur-Sriniketan", "school", 23.6793, 87.6852),
        WestBengalPlace("krishnanagar_jn", "Krishnanagar City Junction", "কৃষ্ণনগর সিটি জংশন", "Nadia", "Krishnanagar I", "railway_station", 23.4008, 88.4961),
        WestBengalPlace("nabadwip_stn", "Nabadwip Dham Railway Station", "নবদ্বীপ ধাম স্টেশন", "Nadia", "Nabadwip", "railway_station", 23.4071, 88.3665),
        WestBengalPlace("ranaghat_jn", "Ranaghat Junction Station", "রানাঘাট জংশন স্টেশন", "Nadia", "Ranaghat I", "railway_station", 23.1753, 88.5670),
        WestBengalPlace("kharagpur_jn", "Kharagpur Junction Railway Station", "খড়গপুর জংশন স্টেশন", "Paschim Medinipur", "Kharagpur I", "railway_station", 22.3396, 87.3250),
        WestBengalPlace("iit_kgp", "IIT Kharagpur Main Gate", "আইআইটি খড়গপুর", "Paschim Medinipur", "Kharagpur I", "school", 22.3149, 87.3105),
        WestBengalPlace("durgapur_stn", "Durgapur Railway Station", "দুর্গাপুর রেল স্টেশন", "Paschim Bardhaman", "Durgapur", "railway_station", 23.4956, 87.3125),
        WestBengalPlace("durgapur_bus", "Durgapur City Centre Bus Stand", "দুর্গাপুর সিটি সেন্টার বাস স্ট্যান্ড", "Paschim Bardhaman", "Durgapur", "bus_stand", 23.5387, 87.2916),
        WestBengalPlace("andal_airport", "Kazi Nazrul Islam Airport (Andal)", "কাজী নজরুল ইসলাম বিমানবন্দর", "Paschim Bardhaman", "Andal", "airport", 23.6223, 87.2430),
        WestBengalPlace("malda_town", "Malda Town Railway Station", "মালদা টাউন স্টেশন", "Malda", "English Bazar", "railway_station", 25.0128, 88.1309),
        WestBengalPlace("njp_siliguri", "New Jalpaiguri (NJP) Station", "নিউ জলপাইগুড়ি স্টেশন", "Jalpaiguri", "Rajganj", "railway_station", 26.6855, 88.4426),
        WestBengalPlace("siliguri_bus", "Tenzing Norgay Central Bus Terminus", "তেনজিং নোরগে বাস টার্মিনাস", "Darjeeling", "Siliguri", "bus_stand", 26.7318, 88.4237),
        WestBengalPlace("bagdogra_airport", "Bagdogra Airport (IXB)", "বাগডোগরা বিমানবন্দর", "Darjeeling", "Matigara", "airport", 26.6812, 88.3286),
        WestBengalPlace("bankura_jn", "Bankura Junction & Town", "বাঁকুড়া জংশন", "Bankura", "Bankura I", "railway_station", 23.2266, 87.0658),
        WestBengalPlace("berhampore_crt", "Berhampore Court Station", "বহরমপুর কোর্ট স্টেশন", "Murshidabad", "Berhampore", "railway_station", 24.0987, 88.2584),
        WestBengalPlace("diamond_harbour", "Diamond Harbour Jetty & Station", "ডায়মন্ড হারবার", "South 24 Parganas", "Diamond Harbour I", "town", 22.1927, 88.1895)
    )

    fun search(query: String, categoryFilter: String? = null): List<WestBengalPlace> {
        val q = query.trim().lowercase()
        return places.filter { place ->
            val catMatch = categoryFilter == null || place.category == categoryFilter
            val textMatch = q.isBlank() ||
                place.nameEn.lowercase().contains(q) ||
                place.nameBn.contains(q) ||
                place.district.lowercase().contains(q) ||
                place.block.lowercase().contains(q) ||
                place.category.lowercase().contains(q)
            catMatch && textMatch
        }
    }
}
