#!/usr/bin/env python3
"""
Generates src/main/resources/data/hotels.json: ~240 fictional hotels across Türkiye.

Hotel names are invented. Districts, cities and landmarks are real places so
the data feels local. Most descriptions are English and about one in five is
Turkish, which is what makes the multilingual rung of the query ladder interesting.

Deterministic: the same seed always produces the same file.
    python3 tools/generate_hotels.py
"""
import json
import math
import random
import re
import unicodedata
from pathlib import Path

SEED = 1017  # 17 October
rnd = random.Random(SEED)
OUT = Path(__file__).resolve().parent.parent / "src/main/resources/data/hotels.json"

# --------------------------------------------------------------------------- destinations
# setting drives the illustration and which tags/types make sense
D = [
    # key, city, district, region, lat, lon, setting, count, roots, landmarks
    ("sultanahmet", "Istanbul", "Sultanahmet", "Marmara", 41.0055, 28.9768, "historic", 14,
     ["Sultan", "Arasta", "Cankurtaran", "Mosaic", "Byzantion", "Lale", "Hippodrome", "Kervan"],
     [("Hagia Sophia", 150, 700), ("Blue Mosque", 200, 800), ("Basilica Cistern", 250, 900), ("Grand Bazaar", 900, 1600)]),
    ("beyoglu", "Istanbul", "Beyoğlu", "Marmara", 41.0340, 28.9770, "city", 12,
     ["Pera", "Cihangir", "Tünel", "İstiklal", "Asmalı", "Tepebaşı", "Galata"],
     [("İstiklal Avenue", 100, 500), ("Taksim Square", 400, 1200), ("Pera Museum", 200, 700)]),
    ("karakoy", "Istanbul", "Karaköy", "Marmara", 41.0240, 28.9770, "bosphorus", 10,
     ["Karaköy", "Galata", "Kemankeş", "Mumhane", "Rıhtım", "Tophane"],
     [("Galata Tower", 300, 800), ("Karaköy ferry pier", 100, 400), ("Istanbul Modern", 300, 900)]),
    ("kadikoy", "Istanbul", "Kadıköy", "Marmara", 40.9880, 29.0270, "bosphorus", 12,
     ["Moda", "Kadıköy", "Yeldeğirmeni", "Bahariye", "Caferağa", "Kalamış", "Fenerbahçe"],
     [("Kadıköy ferry pier", 150, 900), ("Moda seaside park", 100, 700), ("Kadıköy market streets", 100, 500)]),
    ("besiktas", "Istanbul", "Beşiktaş", "Marmara", 41.0430, 29.0070, "bosphorus", 8,
     ["Beşiktaş", "Ortaköy", "Çırağan", "Yıldız", "Akaretler", "Dolmabahçe"],
     [("Dolmabahçe Palace", 300, 1200), ("Ortaköy Mosque", 200, 1500), ("Yıldız Park", 300, 1000)]),
    ("bebek", "Istanbul", "Bebek", "Marmara", 41.0770, 29.0440, "bosphorus", 7,
     ["Bebek", "Arnavutköy", "Rumeli", "Kuruçeşme", "Hisar"],
     [("Rumeli Fortress", 400, 1500), ("Bebek Park", 50, 400)]),
    ("uskudar", "Istanbul", "Üsküdar", "Marmara", 41.0260, 29.0160, "bosphorus", 7,
     ["Üsküdar", "Kuzguncuk", "Çengelköy", "Salacak", "Kız Kulesi"],
     [("Maiden's Tower", 200, 900), ("Kuzguncuk village streets", 100, 600), ("Üsküdar ferry pier", 150, 700)]),
    ("balat", "Istanbul", "Balat", "Marmara", 41.0300, 28.9480, "historic", 6,
     ["Balat", "Fener", "Ayvansaray", "Haliç", "Vodina"],
     [("Golden Horn", 50, 300), ("Chora Museum", 600, 1400), ("Bulgarian St. Stephen Church", 200, 700)]),
    ("levent", "Istanbul", "Levent", "Marmara", 41.0810, 29.0110, "business", 7,
     ["Levent", "Maslak", "Kanyon", "Zorlu", "Büyükdere"],
     [("Levent metro station", 100, 600), ("Kanyon shopping centre", 200, 900), ("Istanbul Expo Center", 15000, 22000)]),
    ("adalar", "Istanbul", "Büyükada", "Marmara", 40.8580, 29.1200, "sea", 5,
     ["Büyükada", "Prinkipo", "Nizam", "Aya Yorgi", "Lunapark"],
     [("Büyükada ferry pier", 200, 900), ("Aya Yorgi hill", 1500, 3000)]),

    ("goreme", "Nevşehir", "Göreme", "Cappadocia", 38.6431, 34.8289, "cave", 14,
     ["Göreme", "Fairy Chimney", "Moonstone", "Rose Valley", "Kapadokya", "Balloon", "Tuff"],
     [("Göreme Open-Air Museum", 600, 1800), ("Love Valley", 1500, 3000), ("balloon launch fields", 800, 2500)]),
    ("urgup", "Nevşehir", "Ürgüp", "Cappadocia", 38.6310, 34.9120, "cave", 8,
     ["Ürgüp", "Temenni", "Mustafapaşa", "Esbelli", "Sinasos"],
     [("Temenni Hill", 200, 800), ("Ürgüp wine cellars", 300, 1000)]),
    ("uchisar", "Nevşehir", "Uçhisar", "Cappadocia", 38.6300, 34.8050, "cave", 6,
     ["Uçhisar", "Castle", "Pigeon Valley", "Argos", "Kaya"],
     [("Uçhisar Castle", 100, 600), ("Pigeon Valley", 300, 900)]),

    ("kaleici", "Antalya", "Kaleiçi", "Mediterranean", 36.8840, 30.7060, "historic", 8,
     ["Kaleiçi", "Hadrian", "Yivli", "Old Harbour", "Kesik Minare"],
     [("Hadrian's Gate", 100, 600), ("Antalya old harbour", 100, 500)]),
    ("lara", "Antalya", "Lara", "Mediterranean", 36.8500, 30.8500, "sea", 9,
     ["Lara", "Kundu", "Düden", "Turquoise", "Aspendos"],
     [("Lara Beach", 0, 200), ("Düden Waterfalls", 1500, 6000), ("Antalya Airport", 9000, 15000)]),
    ("konyaalti", "Antalya", "Konyaaltı", "Mediterranean", 36.8650, 30.6300, "sea", 6,
     ["Konyaaltı", "Beach Park", "Olympos", "Toros"],
     [("Konyaaltı Beach", 50, 300), ("Antalya Aquarium", 500, 1500)]),
    ("alanya", "Antalya", "Alanya", "Mediterranean", 36.5440, 31.9990, "sea", 8,
     ["Alanya", "Kleopatra", "Damlataş", "Kızılkule", "Tersane"],
     [("Cleopatra Beach", 50, 600), ("Damlataş Cave", 300, 1200), ("Alanya Castle", 1000, 2500)]),
    ("kas", "Antalya", "Kaş", "Mediterranean", 36.2020, 29.6380, "sea", 7,
     ["Kaş", "Antiphellos", "Meis", "Çukurbağ", "Limanağzı"],
     [("Kaş harbour", 100, 700), ("Kaputaş Beach", 18000, 22000), ("Antiphellos theatre", 300, 1000)]),
    ("kalkan", "Antalya", "Kalkan", "Mediterranean", 36.2650, 29.4140, "sea", 5,
     ["Kalkan", "Kalamar", "Patara", "Islamlar"],
     [("Kalkan old town", 100, 800), ("Patara Beach", 14000, 18000)]),
    ("oludeniz", "Muğla", "Ölüdeniz", "Aegean", 36.5490, 29.1150, "sea", 7,
     ["Ölüdeniz", "Babadağ", "Kelebekler", "Belcekız", "Lycian"],
     [("Blue Lagoon", 100, 700), ("Babadağ paragliding launch", 9000, 14000), ("Butterfly Valley boats", 200, 600)]),
    ("bodrum", "Muğla", "Bodrum", "Aegean", 37.0350, 27.4300, "sea", 9,
     ["Bodrum", "Halikarnas", "Gümbet", "Kumbahçe", "Mausolus"],
     [("Bodrum Castle", 200, 1200), ("Bodrum marina", 200, 900), ("Bar Street", 200, 1000)]),
    ("yalikavak", "Muğla", "Yalıkavak", "Aegean", 37.1050, 27.2920, "sea", 6,
     ["Yalıkavak", "Palmarina", "Geriş", "Windmill"],
     [("Yalıkavak Palmarina", 100, 900), ("Yalıkavak windmills", 300, 1200)]),
    ("datca", "Muğla", "Datça", "Aegean", 36.7270, 27.6880, "village", 5,
     ["Datça", "Knidos", "Eski Datça", "Reşadiye", "Badem"],
     [("Eski Datça stone houses", 100, 800), ("Knidos ruins", 30000, 36000)]),
    ("alacati", "İzmir", "Alaçatı", "Aegean", 38.2820, 26.3750, "village", 9,
     ["Alaçatı", "Taş Ev", "Değirmen", "Pazaryeri", "Hacı Memiş", "Ilıca"],
     [("Alaçatı windmills", 200, 900), ("Alaçatı windsurf beach", 2500, 4000), ("Çeşme Castle", 8000, 10000)]),
    ("alsancak", "İzmir", "Alsancak", "Aegean", 38.4380, 27.1430, "city", 5,
     ["Alsancak", "Kordon", "Kıbrıs Şehitleri", "Smyrna"],
     [("Kordon promenade", 50, 400), ("Kemeraltı Bazaar", 1500, 2500)]),
    ("ayvalik", "Balıkesir", "Cunda", "Aegean", 39.3250, 26.6400, "village", 5,
     ["Cunda", "Ayvalık", "Taksiyarhis", "Sevim", "Zeytin"],
     [("Cunda harbour", 50, 500), ("Taksiyarhis Church", 300, 900), ("Şeytan Sofrası viewpoint", 9000, 12000)]),
    ("bozcaada", "Çanakkale", "Bozcaada", "Aegean", 39.8340, 26.0670, "village", 4,
     ["Bozcaada", "Tenedos", "Ayazma", "Polente"],
     [("Bozcaada Castle", 100, 700), ("Ayazma Beach", 5000, 7000)]),
    ("assos", "Çanakkale", "Assos", "Aegean", 39.4880, 26.3370, "village", 4,
     ["Assos", "Behramkale", "Athena", "Kadırga"],
     [("Temple of Athena", 200, 1200), ("Assos old harbour", 100, 600)]),
    ("pamukkale", "Denizli", "Pamukkale", "Aegean", 37.9200, 29.1200, "village", 4,
     ["Pamukkale", "Hierapolis", "Travertine", "Karahayıt"],
     [("Pamukkale travertines", 300, 1500), ("Hierapolis ancient city", 500, 2000)]),

    ("uzungol", "Trabzon", "Uzungöl", "Black Sea", 40.6200, 40.2900, "lake", 6,
     ["Uzungöl", "Karadeniz", "Haldizen", "Serender", "Yayla"],
     [("Uzungöl lake shore", 50, 500), ("Uzungöl viewing terrace", 600, 2000)]),
    ("ayder", "Rize", "Ayder", "Black Sea", 40.9530, 41.1010, "mountain", 5,
     ["Ayder", "Kaçkar", "Gelintülü", "Yayla", "Fırtına"],
     [("Ayder hot springs", 100, 800), ("Kaçkar trailheads", 500, 3000), ("Gelintülü waterfall", 300, 1200)]),
    ("uludag", "Bursa", "Uludağ", "Marmara", 40.1000, 29.1200, "mountain", 6,
     ["Uludağ", "Olympos", "Kirazlıyayla", "Sarıalan", "Bakacak"],
     [("Uludağ ski lifts", 50, 500), ("Uludağ cable car upper station", 2000, 6000)]),
    ("kartepe", "Kocaeli", "Kartepe", "Marmara", 40.6800, 30.0700, "mountain", 4,
     ["Kartepe", "Maşukiye", "Sapanca", "Kuzu Yayla"],
     [("Kartepe ski centre", 100, 1500), ("Lake Sapanca", 6000, 12000)]),
    ("abant", "Bolu", "Abant", "Black Sea", 40.6040, 31.2780, "lake", 3,
     ["Abant", "Gölcük", "Yedigöller", "Köroğlu"],
     [("Lake Abant walking trail", 50, 400), ("Yedigöller National Park", 45000, 55000)]),
    ("safranbolu", "Karabük", "Safranbolu", "Black Sea", 41.2440, 32.6930, "village", 4,
     ["Safranbolu", "Çarşı", "Cinci", "Hıdırlık", "Bağlar"],
     [("Cinci Han", 100, 600), ("Safranbolu old bazaar", 50, 500)]),
    ("mardin", "Mardin", "Mardin Old Town", "Southeastern Anatolia", 37.3130, 40.7350, "historic", 5,
     ["Mardin", "Mezopotamya", "Kasımiye", "Zinciriye", "Deyrulzafaran"],
     [("Zinciriye Madrasa", 200, 800), ("Mardin old bazaar", 100, 500), ("Deyrulzafaran Monastery", 4000, 6000)]),
    ("ankara", "Ankara", "Çankaya", "Central Anatolia", 39.9080, 32.8600, "business", 5,
     ["Çankaya", "Kavaklıdere", "Tunalı", "Kızılay", "Anıtkabir"],
     [("Anıtkabir", 1200, 3000), ("Tunalı Hilmi street", 100, 800), ("Ankara congress centre", 1500, 4000)]),
]

# --------------------------------------------------------------------------- types
TYPES = {
    # type: (stars range, base price EUR, settings where it appears)
    "Boutique Hotel": ((3, 5), 120, {"historic", "city", "bosphorus", "village", "sea", "cave"}),
    "Resort": ((4, 5), 210, {"sea"}),
    "Cave Hotel": ((3, 5), 150, {"cave"}),
    "Business Hotel": ((4, 5), 140, {"business", "city", "bosphorus"}),
    "Hostel": ((1, 2), 28, {"historic", "city", "bosphorus", "sea", "cave"}),
    "Guesthouse": ((2, 3), 60, {"village", "historic", "sea", "lake", "mountain", "cave"}),
    "Apartment Hotel": ((3, 4), 95, {"city", "bosphorus", "business", "sea"}),
    "Villa": ((4, 5), 320, {"sea", "village"}),
    "Ottoman Mansion": ((4, 5), 190, {"historic", "bosphorus", "village"}),
    "Waterfront Mansion": ((5, 5), 480, {"bosphorus"}),
    "Spa Hotel": ((4, 5), 170, {"mountain", "lake", "sea", "village", "city"}),
    "Mountain Lodge": ((3, 4), 110, {"mountain", "lake"}),
    "Bungalow": ((2, 3), 70, {"lake", "mountain", "sea"}),
    "Glamping": ((2, 3), 85, {"sea", "village", "lake", "mountain"}),
    "Stone House": ((3, 4), 115, {"village", "historic", "cave"}),
}

TYPE_WORDS = {
    "Boutique Hotel": ["Hotel", "Boutique Hotel", "House", "Suites", "Rooms"],
    "Resort": ["Resort", "Beach Resort", "Resort & Spa", "Bay Resort"],
    "Cave Hotel": ["Cave Hotel", "Cave Suites", "Cave House", "Rock Rooms"],
    "Business Hotel": ["Business Hotel", "Hotel", "Tower Hotel", "Plaza"],
    "Hostel": ["Hostel", "Backpackers", "Bunk House"],
    "Guesthouse": ["Pansiyon", "Guesthouse", "Inn"],
    "Apartment Hotel": ["Apartments", "Residence", "Lofts"],
    "Villa": ["Villa", "Villas", "Estate"],
    "Ottoman Mansion": ["Konak", "Mansion", "Konağı"],
    "Waterfront Mansion": ["Yalı", "Yalısı", "Waterfront Mansion"],
    "Spa Hotel": ["Spa Hotel", "Thermal & Spa", "Wellness Hotel"],
    "Mountain Lodge": ["Lodge", "Mountain Lodge", "Chalet"],
    "Bungalow": ["Bungalows", "Wooden Houses", "Cabins"],
    "Glamping": ["Glamping", "Eco Camp", "Tents"],
    "Stone House": ["Stone House", "Taş Ev", "Stone Rooms"],
}
NAME_EXTRA = ["Lantern", "Olive", "Fig", "Mavi", "Lighthouse", "Cypress", "Saffron", "Pomegranate", "Jasmine",
              "Anchor", "Terrace", "Garden", "Moon", "Orchard", "Vine", "Salt", "Tulip", "Pine", "Amber", "Copper"]

# --------------------------------------------------------------------------- text banks
OPENERS_EN = {
    "Boutique Hotel": ["A {n}-room boutique hotel in a restored early-20th-century building, with hand-picked furniture and local art on every floor.",
                       "This small, design-led hotel has {n} rooms and the feel of a friend's stylish apartment rather than a chain.",
                       "Behind a carefully restored façade, {n} individually decorated rooms mix original stonework with modern comfort."],
    "Resort": ["A large all-inclusive resort with {n} rooms, several pools and its own stretch of sand.",
               "This beachfront resort spreads {n} rooms across landscaped gardens that run down to the water.",
               "A full-service resort with {n} rooms, water slides, three restaurants and an evening entertainment programme."],
    "Cave Hotel": ["{n} suites carved into the soft volcanic rock, with arched stone ceilings and niches lit by lanterns.",
                   "A cave hotel with {n} rooms hollowed out of a fairy chimney hillside; each one keeps a naturally cool temperature in summer.",
                   "Hand-carved cave rooms ({n} of them) open onto terraces looking over the valley and the hot-air balloons at dawn."],
    "Business Hotel": ["A modern business hotel with {n} rooms, meeting rooms for up to 300 people and an executive lounge.",
                       "Built for working travellers: {n} quiet rooms with large desks, fast connections and a 24-hour gym.",
                       "This glass-fronted hotel with {n} rooms sits in the financial district, close to offices and the metro."],
    "Hostel": ["A sociable hostel with dorms and {n} private rooms, a shared kitchen and nightly meet-ups.",
               "Cheap, clean and lively: bunk dorms plus {n} small private rooms, with a common room full of travellers.",
               "A budget-friendly hostel with {n} rooms and dorm beds, lockers and a rooftop where guests swap travel tips."],
    "Guesthouse": ["A family-run guesthouse with {n} simple rooms, where the owners cook breakfast themselves.",
                   "A small pension with {n} rooms; the family that runs it has lived here for three generations.",
                   "{n} homely rooms in a family house, with a shared garden and plenty of local advice from the hosts."],
    "Apartment Hotel": ["Serviced apartments ({n} units) with kitchenettes, washing machines and weekly cleaning, ideal for longer stays.",
                        "{n} self-contained apartments with a living area and kitchenette, plus hotel-style reception.",
                        "An apartment hotel with {n} studios and one-bedroom flats for travellers who like their own space."],
    "Villa": ["A private villa compound with {n} villas, each with its own infinity pool and outdoor kitchen.",
              "{n} secluded villas set among olive trees, each sleeping six with a private pool.",
              "Luxury villas ({n} in total) with sea-facing terraces, daily housekeeping and a private chef on request."],
    "Ottoman Mansion": ["A 19th-century Ottoman mansion (konak) restored into {n} rooms with painted wooden ceilings and courtyard fountains.",
                        "This listed Ottoman-era konak keeps its original wooden staircase, bay windows and {n} high-ceilinged rooms.",
                        "Once the home of a merchant family, this mansion now has {n} rooms furnished with antiques and Anatolian kilims."],
    "Waterfront Mansion": ["A historic wooden yalı right on the Bosphorus, with {n} rooms and a private boat jetty.",
                           "This 18th-century waterfront mansion sits so close to the Bosphorus that ships seem to pass through the garden; {n} suites.",
                           "A restored waterfront yalı with {n} rooms, marble hammam and breakfast served on the quay."],
    "Spa Hotel": ["A wellness hotel with {n} rooms built around a thermal spa, hammam and indoor pool.",
                  "{n} calm rooms, a large spa with sauna, steam room and treatment rooms, and yoga classes every morning.",
                  "A spa hotel of {n} rooms where the day revolves around thermal pools, massages and long healthy breakfasts."],
    "Mountain Lodge": ["A timber mountain lodge with {n} rooms, open fireplaces and boot rooms for skiers and hikers.",
                       "{n} wood-panelled rooms in an alpine-style lodge, with a sauna for after a day on the slopes.",
                       "A cosy lodge with {n} rooms, a big stone fireplace in the lounge and views of snowy peaks."],
    "Bungalow": ["{n} wooden bungalows scattered through the forest, each with a small porch and a stove.",
                 "Simple wooden cabins ({n}) by the water, with hammocks, barbecue areas and fresh mountain air.",
                 "{n} traditional Black Sea style wooden houses on stilts, surrounded by tea gardens and pine forest."],
    "Glamping": ["An eco glamping site with {n} safari tents on wooden decks, solar power and outdoor showers.",
                 "{n} bell tents with real beds, set in an olive grove; evenings are spent around the campfire.",
                 "Glamping in {n} canvas tents and dome cabins, with a farm-to-table kitchen and stargazing nights."],
    "Stone House": ["A restored stone house with {n} rooms, thick walls, wooden shutters and a vine-covered courtyard.",
                    "{n} rooms in a traditional whitewashed stone house with blue window frames.",
                    "An old stone house turned into {n} rooms, keeping the arched doorways and stone floors."],
}
OPENERS_TR = {
    "Boutique Hotel": ["Restore edilmiş eski bir binada {n} odalı butik otel; her katta yerel sanatçıların eserleri var.",
                       "{n} odalı, tasarım odaklı küçük bir otel; bir zincir otelden çok şık bir ev havası taşıyor."],
    "Resort": ["{n} odalı, birkaç havuzlu ve kendine ait kumsalı olan büyük bir her şey dahil tatil köyü.",
               "Denize sıfır bu tatil köyünde {n} oda, suya kadar inen bahçeler arasında yer alıyor."],
    "Cave Hotel": ["Yumuşak volkanik kayaya oyulmuş {n} süit; kemerli taş tavanlar ve fenerlerle aydınlanan nişler.",
                   "Peri bacalarının arasına oyulmuş {n} odalı mağara otel; odalar yazın doğal olarak serin kalıyor."],
    "Business Hotel": ["{n} odalı modern bir iş oteli; 300 kişilik toplantı salonları ve yönetici salonu var.",
                       "İş seyahati için tasarlanmış {n} sessiz oda, geniş çalışma masaları ve 24 saat açık spor salonu."],
    "Hostel": ["Yatakhaneleri ve {n} özel odası olan, ortak mutfaklı, sosyal bir hostel.",
               "Ucuz, temiz ve hareketli: ranzalı yatakhaneler ve {n} küçük özel oda."],
    "Guesthouse": ["Kahvaltıyı ev sahiplerinin kendisinin hazırladığı, {n} sade odalı bir aile pansiyonu.",
                   "Üç kuşaktır aynı ailenin işlettiği {n} odalı küçük bir pansiyon."],
    "Apartment Hotel": ["Mutfaklı, çamaşır makineli {n} apart daire; uzun konaklamalar için ideal.",
                        "{n} bağımsız daire ve otel tarzı resepsiyon."],
    "Villa": ["Her biri özel sonsuzluk havuzlu {n} villadan oluşan özel bir villa kompleksi.",
              "Zeytin ağaçları arasında, her biri özel havuzlu {n} müstakil villa."],
    "Ottoman Mansion": ["Boyalı ahşap tavanları ve avlu çeşmesiyle {n} odaya dönüştürülmüş 19. yüzyıl Osmanlı konağı.",
                        "Orijinal ahşap merdivenini ve cumbalarını koruyan, {n} yüksek tavanlı odalı tescilli bir konak."],
    "Waterfront Mansion": ["Boğaz'ın hemen kıyısında, {n} odalı ve özel iskeleli tarihi bir ahşap yalı.",
                           "Gemilerin bahçeden geçiyormuş gibi göründüğü 18. yüzyıldan kalma bir yalı; {n} süit."],
    "Spa Hotel": ["Termal spa, hamam ve kapalı havuz etrafında kurulmuş {n} odalı bir sağlık oteli.",
                  "{n} huzurlu oda, sauna ve buhar odalı geniş bir spa ve her sabah yoga dersleri."],
    "Mountain Lodge": ["{n} odalı, şömineli ahşap bir dağ evi; kayakçılar ve yürüyüşçüler için ideal.",
                       "Kayak sonrası saunası olan, alp tarzı ahşap kaplı {n} odalı bir dağ oteli."],
    "Bungalow": ["Ormanın içine dağılmış, her biri küçük verandalı ve sobalı {n} ahşap bungalov.",
                 "Çay bahçeleri ve çam ormanıyla çevrili, {n} geleneksel Karadeniz tipi ahşap ev."],
    "Glamping": ["Ahşap platformlar üzerinde {n} safari çadırı, güneş enerjisi ve açık hava duşlarıyla bir eko glamping alanı.",
                 "Zeytinlikte gerçek yataklı {n} çan çadır; akşamlar kamp ateşi başında geçiyor."],
    "Stone House": ["Kalın duvarlı, ahşap kepenkli ve asmalı avlulu {n} odalı restore edilmiş bir taş ev.",
                    "Mavi pencereli, beyaz badanalı geleneksel bir taş evde {n} oda."],
}

TAG_EN = {
    "beach": ["Sun loungers and umbrellas wait on the private beach, a few steps from the rooms.",
              "The hotel sits right on the seafront, so you can swim straight off the jetty in the morning.",
              "A sandy beach with clear, shallow water starts just across the garden."],
    "romantic": ["Candle-lit dinners on the terrace and suites with private hot tubs make it a favourite for couples and honeymooners.",
                 "Sunset views, rose petals on arrival and a quiet adults-only terrace make it ideal for a romantic escape.",
                 "Many guests come here for anniversaries: rooms have big bathtubs and the restaurant does a memorable dinner for two."],
    "family": ["Interconnecting family rooms, a kids' club and a shallow children's pool keep younger guests busy.",
               "Families are well looked after, with cots, high chairs, a playground and babysitting on request.",
               "There is a kids' club with daily activities and family suites that sleep four."],
    "business": ["Meeting rooms, a business centre and express laundry make it practical for work trips.",
                 "Corporate travellers appreciate the fast check-in, desks in every room and the airport shuttle.",
                 "Conference facilities and a quiet lobby lounge make it easy to host meetings."],
    "remote-work": ["Fibre internet (500 Mbps), ergonomic chairs and a bright co-working lounge make long stays easy for remote workers.",
                    "Digital nomads like the dedicated co-working room, reliable high-speed wifi and monthly rates.",
                    "Every room has a proper desk and the shared workspace downstairs has standing desks and free coffee all day."],
    "quiet": ["Rooms face a leafy inner courtyard, so nights are remarkably quiet.",
              "Away from traffic and nightlife, it is a peaceful place where you mostly hear birds.",
              "Thick walls and a secluded location mean guests describe it as calm and silent."],
    "nightlife": ["Bars, clubs and live-music venues are a short walk away, so expect street noise until late at weekends.",
                  "Right in the middle of the nightlife district: great for going out, less so for early sleepers.",
                  "The rooftop bar has DJs on Fridays and Saturdays and the area stays lively until the early hours."],
    "historic": ["The building is listed as a historic monument, and the old city's main sights are within walking distance.",
                 "History is everywhere: original Ottoman tiles, Byzantine stones in the cellar and centuries-old streets outside.",
                 "Staying here feels like a step back in time, with restored period details throughout."],
    "wellness": ["The spa has a traditional hammam, sauna, steam room and a menu of massages.",
                 "Wellness is a focus, with daily yoga, a thermal pool and healthy breakfast options.",
                 "After a long day, guests unwind in the hammam and the heated indoor pool."],
    "adventure": ["Staff can organise hiking, rafting, paragliding and jeep safaris.",
                  "It is a good base for outdoor adventures: trekking routes start from the door and bikes are available.",
                  "Guides from the hotel lead canyon walks, diving trips and mountain bike tours."],
    "budget": ["Rates are among the lowest in the area, without compromising on cleanliness.",
               "Good value for money: simple rooms, friendly staff and a central location.",
               "An affordable choice for travellers watching their budget."],
    "luxury": ["Expect five-star service: butler service, Egyptian cotton sheets and a pillow menu.",
               "Marble bathrooms, designer furniture and attentive staff deliver a truly luxurious stay.",
               "Fine dining, a champagne bar and a chauffeur service set it apart."],
    "foodie": ["The restaurant is known across the region for slow-cooked local dishes and an excellent wine list.",
               "Cooking classes with the chef and visits to the local market are popular with food lovers.",
               "The kitchen works with nearby farms; dinner is a tasting menu of regional recipes."],
    "eco": ["Solar panels, rainwater collection and a vegetable garden make it one of the region's most sustainable stays.",
            "It is a certified eco lodge: plastic-free, powered by renewables and built from local materials.",
            "The owners run a zero-waste kitchen and grow most of the vegetables on site."],
    "nature": ["Surrounded by forest and green hills, it is perfect for walks and fresh air.",
               "Nature lovers wake up to mountain views, streams and birdsong.",
               "Hiking trails through pine forests start right behind the property."],
    "ski": ["Ski-in ski-out access, a ski storage room and equipment rental make winter trips easy.",
            "The lifts are a few minutes away and the hotel runs a ski shuttle in the morning.",
            "In winter it fills with skiers; there is a ski school and boot dryers in the basement."],
    "design": ["Interiors were designed by a local studio, mixing concrete, brass and Anatolian textiles.",
               "Minimalist design, curated art and a well-stocked library give it a gallery feel.",
               "Every corner is photogenic, from the terrazzo floors to the hand-made ceramics."],
    "view": ["Most rooms have balconies with sweeping views.",
             "The panoramic terrace has one of the best views in town.",
             "Floor-to-ceiling windows frame the view from every room."],
}
TAG_TR = {
    "beach": ["Odalardan birkaç adım ötede özel plajda şezlong ve şemsiyeler sizi bekliyor.",
              "Otel denize sıfır; sabahları iskeleden doğrudan denize girebilirsiniz."],
    "romantic": ["Terasta mum ışığında akşam yemekleri ve jakuzili süitler onu çiftlerin ve balayı çiftlerinin favorisi yapıyor.",
                 "Gün batımı manzarası ve sakin, yetişkinlere özel terasıyla romantik bir kaçamak için ideal."],
    "family": ["Bağlantılı aile odaları, çocuk kulübü ve sığ çocuk havuzu küçük misafirleri meşgul ediyor.",
               "Bebek karyolası, mama sandalyesi, oyun parkı ve istek üzerine bebek bakıcılığı var."],
    "business": ["Toplantı odaları, iş merkezi ve ekspres çamaşırhane iş seyahatlerini kolaylaştırıyor.",
                 "Hızlı giriş, her odada çalışma masası ve havalimanı servisi iş seyahatinde olanlar için ideal."],
    "remote-work": ["Fiber internet (500 Mbps), ergonomik sandalyeler ve aydınlık bir ortak çalışma alanı uzaktan çalışanlar için uzun konaklamayı kolaylaştırıyor.",
                    "Dijital göçebeler özel ortak çalışma odasını, güvenilir hızlı interneti ve aylık fiyatları seviyor."],
    "quiet": ["Odalar yeşil bir iç avluya bakıyor; geceler oldukça sessiz.",
              "Trafikten ve gece hayatından uzak, çoğunlukla sadece kuş seslerinin duyulduğu huzurlu bir yer."],
    "nightlife": ["Barlar, kulüpler ve canlı müzik mekânları yürüme mesafesinde; hafta sonları geç saatlere kadar sokak gürültüsü olabilir.",
                  "Gece hayatının tam ortasında: eğlenmek için harika, erken yatanlar için pek değil."],
    "historic": ["Bina tescilli tarihi eser; eski şehrin önemli noktaları yürüme mesafesinde.",
                 "Orijinal Osmanlı çinileri ve yüzyıllık sokaklarla burada kalmak zamanda yolculuk gibi."],
    "wellness": ["Spa'da geleneksel hamam, sauna, buhar odası ve masaj seçenekleri var.",
                 "Her gün yoga, termal havuz ve sağlıklı kahvaltı seçenekleri sunuluyor."],
    "adventure": ["Ekip trekking, rafting, yamaç paraşütü ve jip safarisi organize edebiliyor.",
                  "Yürüyüş rotaları kapıdan başlıyor, bisiklet kiralamak mümkün."],
    "budget": ["Fiyatlar bölgedeki en uygunlar arasında, temizlikten ödün vermeden.",
               "Fiyatına göre çok iyi: sade odalar, güler yüzlü ekip ve merkezi konum."],
    "luxury": ["Beş yıldızlı hizmet: butler servisi, Mısır pamuğu çarşaflar ve yastık menüsü.",
               "Mermer banyolar, tasarım mobilyalar ve özenli bir ekip gerçek bir lüks deneyimi sunuyor."],
    "foodie": ["Restoran, ağır ateşte pişen yöresel yemekleri ve zengin şarap listesiyle bölgede tanınıyor.",
               "Şefle yemek atölyeleri ve yerel pazar ziyaretleri yemek meraklılarının favorisi."],
    "eco": ["Güneş panelleri, yağmur suyu toplama ve sebze bahçesiyle bölgenin en sürdürülebilir konaklamalarından biri.",
            "Plastiksiz, yenilenebilir enerjiyle çalışan, yerel malzemelerle inşa edilmiş sertifikalı bir eko tesis."],
    "nature": ["Orman ve yeşil tepelerle çevrili; yürüyüş ve temiz hava için mükemmel.",
               "Doğa severler dağ manzarası, dereler ve kuş sesleriyle uyanıyor."],
    "ski": ["Kayakla giriş-çıkış, kayak odası ve ekipman kiralama kış tatilini kolaylaştırıyor.",
            "Teleferikler birkaç dakika uzaklıkta; otel sabahları kayak servisi düzenliyor."],
    "design": ["İç mekânlar yerel bir stüdyo tarafından beton, pirinç ve Anadolu dokumalarıyla tasarlandı.",
               "Minimalist tasarım ve özenle seçilmiş sanat eserleri otele galeri havası veriyor."],
    "view": ["Odaların çoğunda geniş manzaralı balkonlar var.",
             "Panoramik teras şehrin en iyi manzaralarından birine sahip."],
}
BREAKFAST_EN = ["Breakfast is a generous Turkish spread with village cheeses, olives, honey in the comb and fresh simit.",
                "The breakfast buffet is decent but not memorable; coffee is good.",
                "Breakfast is served in the garden: eggs cooked to order, homemade jams and fresh fruit.",
                "A simple continental breakfast is included.",
                "Breakfast is not included, but there are plenty of cafés nearby.",
                "The highlight for many guests is the long Turkish breakfast with menemen and gözleme made on the spot."]
# aligned index-by-index with BREAKFAST_EN
BREAKFAST_TR = ["Kahvaltı köy peynirleri, zeytinler, petek bal ve taze simitle zengin bir Türk kahvaltısı.",
                "Açık büfe kahvaltı fena değil ama akılda kalıcı da değil; kahvesi güzel.",
                "Kahvaltı bahçede servis ediliyor: siparişe göre yumurta, ev yapımı reçeller ve taze meyve.",
                "Sade bir kontinental kahvaltı fiyata dahil.",
                "Kahvaltı dahil değil, ancak yakında birçok kafe var.",
                "Birçok misafir için en güzel kısım, yerinde yapılan menemen ve gözlemeyle uzun Türk kahvaltısı."]

REVIEW_BANK = {
    "breakfast_good": ["Best breakfast of our whole trip, the homemade jams were amazing.", "Breakfast was a feast, we skipped lunch every day.",
                       "Kahvaltı muhteşemdi, her şey tazeydi."],
    "breakfast_bad": ["Breakfast was repetitive after a few days.", "Breakfast was a bit basic for the price."],
    "staff": ["The staff went out of their way to help us, they even booked our boat tour.", "Super friendly reception team, felt like family.",
              "Personel çok ilgili ve güler yüzlüydü."],
    "clean": ["Spotlessly clean room and comfortable bed.", "Very clean, towels changed every day."],
    "noise": ["Lovely place but the street gets loud on Friday and Saturday nights, bring earplugs.", "Noise from the bars until 2am, not for light sleepers."],
    "quiet": ["So peaceful, we slept better than at home.", "Completely silent at night, only birds in the morning.", "Çok sessiz ve huzurlu bir yerdi."],
    "wifi_good": ["Wifi was fast enough for video calls all day.", "I worked remotely for two weeks here, the internet never dropped."],
    "wifi_bad": ["Wifi was weak in the rooms, only worked in the lobby."],
    "view": ["The view from the terrace at sunset is unforgettable.", "We could see the sea from our bed.", "Manzara inanılmazdı."],
    "kids": ["Our kids loved the kids' club and the pool.", "Great for families, our toddler was very welcome."],
    "pets": ["They welcomed our dog with a bed and treats!", "Pet friendly for real, our dog was spoiled by the staff."],
    "location": ["Perfect location, everything is walking distance.", "Location is great, close to the ferry."],
    "value": ["Great value for money.", "A bit pricey but worth it.", "Overpriced for what you get."],
    "romantic": ["Perfect for our honeymoon, the private dinner was magical.", "Came for our anniversary, very romantic."],
    "balloons": ["Woke up at dawn to watch the balloons from the terrace, magical.", "Balloons floated right past our room in the morning."],
    "ski": ["We skied straight to the lift, very convenient.", "Great après-ski by the fireplace."],
    "spa": ["The hammam and massage were the highlight.", "The spa is small but very relaxing."],
}
NAMES = [("Elif", "Türkiye"), ("Mehmet", "Türkiye"), ("Zeynep", "Türkiye"), ("Can", "Türkiye"), ("Ayşe", "Türkiye"), ("Burak", "Türkiye"),
         ("Anna", "Germany"), ("Lukas", "Germany"), ("Sophie", "France"), ("Hugo", "France"), ("Emma", "United Kingdom"), ("James", "United Kingdom"),
         ("Giulia", "Italy"), ("Marco", "Italy"), ("Sara", "Spain"), ("Pablo", "Spain"), ("Olivia", "United States"), ("Noah", "United States"),
         ("Yuki", "Japan"), ("Mina", "South Korea"), ("Lars", "Netherlands"), ("Ingrid", "Norway"), ("Aisha", "UAE"), ("Dmitri", "Bulgaria")]

# --------------------------------------------------------------------------- helpers
def slug(s):
    s = s.replace("ı", "i").replace("İ", "I")
    s = unicodedata.normalize("NFKD", s).encode("ascii", "ignore").decode()
    return re.sub(r"[^a-z0-9]+", "-", s.lower()).strip("-")


def pick_tags(setting, typ):
    t = set()
    base = {
        "historic": ["historic", "design", "foodie", "nightlife", "quiet", "romantic", "budget", "view"],
        "city": ["nightlife", "design", "foodie", "remote-work", "business", "budget", "view"],
        "bosphorus": ["view", "romantic", "foodie", "design", "remote-work", "nightlife", "quiet", "luxury"],
        "business": ["business", "remote-work", "quiet", "wellness"],
        "sea": ["beach", "family", "romantic", "nightlife", "adventure", "view", "wellness", "quiet"],
        "cave": ["romantic", "view", "historic", "adventure", "quiet", "design"],
        "village": ["quiet", "foodie", "romantic", "design", "eco", "remote-work", "nature"],
        "lake": ["nature", "quiet", "family", "eco", "adventure", "view"],
        "mountain": ["nature", "ski", "adventure", "quiet", "family", "wellness"],
    }[setting]
    k = rnd.choice([2, 3, 3, 4])
    t.update(rnd.sample(base, k=min(k, len(base))))
    forced = {
        "Resort": ["family", "beach"], "Business Hotel": ["business"], "Hostel": ["budget", "nightlife"],
        "Spa Hotel": ["wellness"], "Waterfront Mansion": ["luxury", "view", "romantic"], "Villa": ["luxury", "romantic"],
        "Glamping": ["eco", "nature"], "Mountain Lodge": ["nature"], "Apartment Hotel": ["remote-work"],
        "Ottoman Mansion": ["historic"], "Cave Hotel": ["view"], "Guesthouse": ["budget"],
    }.get(typ, [])
    t.update(forced)
    if setting == "sea" and typ not in ("Hostel",) and rnd.random() < 0.7:
        t.add("beach")
    if setting == "mountain" and typ in ("Mountain Lodge", "Spa Hotel") and rnd.random() < 0.7:
        t.add("ski")
    if "quiet" in t and "nightlife" in t:
        t.discard(rnd.choice(["quiet", "nightlife"]))
    if "luxury" in t:
        t.discard("budget")
    if typ == "Hostel":
        t -= {"luxury", "quiet", "romantic", "wellness"}
    if typ == "Business Hotel":
        t -= {"romantic", "eco", "nightlife"}
    return sorted(t)


def pick_amenities(setting, typ, tags, stars):
    a = {"Free WiFi", "Air conditioning"}
    if setting in ("mountain", "lake"):
        a.discard("Air conditioning")
    if stars >= 3 or rnd.random() < 0.5:
        a.add("Breakfast included")
    if stars >= 4:
        a.update(["Restaurant", "Bar", "24h reception"])
    if "beach" in tags:
        a.add("Beach access")
    if setting == "sea" and ("view" in tags or rnd.random() < 0.6):
        a.add("Sea view")
    if setting == "bosphorus" and ("view" in tags or typ == "Waterfront Mansion" or rnd.random() < 0.35):
        a.add("Bosphorus view")
    if setting == "lake" and rnd.random() < 0.8:
        a.add("Lake view")
    if setting == "mountain" and rnd.random() < 0.8:
        a.add("Mountain view")
    if typ in ("Resort", "Villa") or ("family" in tags and rnd.random() < 0.7) or (stars >= 4 and setting in ("sea", "village", "cave") and rnd.random() < 0.5):
        a.add("Pool")
    if "wellness" in tags or typ == "Spa Hotel":
        a.update(["Spa", "Hammam"])
        if rnd.random() < 0.6:
            a.add("Indoor pool")
    if stars >= 4 and rnd.random() < 0.7:
        a.add("Gym")
    if "family" in tags:
        a.update(["Family rooms"] + (["Kids club"] if rnd.random() < 0.6 else []))
    if "business" in tags:
        a.update(["Meeting rooms", "Airport shuttle"] if rnd.random() < 0.7 else ["Meeting rooms"])
    if "remote-work" in tags:
        a.add("Co-working space")
    if setting in ("mountain", "lake") or typ in ("Mountain Lodge", "Ottoman Mansion", "Stone House") and rnd.random() < 0.6:
        if rnd.random() < 0.75:
            a.add("Fireplace")
    if "ski" in tags:
        a.add("Ski storage")
    if "romantic" in tags and rnd.random() < 0.5:
        a.add("Hot tub")
    if typ in ("Apartment Hotel", "Villa"):
        a.add("Kitchenette")
    if setting in ("sea", "cave", "village", "bosphorus") and rnd.random() < 0.45:
        a.add("Rooftop terrace")
    if setting not in ("historic",) and rnd.random() < 0.55:
        a.add("Parking")
    if rnd.random() < 0.22 or ("nature" in tags and rnd.random() < 0.35) or (typ in ("Bungalow", "Glamping") and rnd.random() < 0.6):
        a.add("Pet friendly")
    if rnd.random() < 0.3 and stars >= 3:
        a.add("Wheelchair accessible")
    if setting in ("village", "lake", "mountain", "sea") and rnd.random() < 0.5:
        a.add("Garden")
    if stars >= 4 and rnd.random() < 0.25:
        a.add("EV charging")
    if "luxury" in tags:
        a.update(["Spa", "Restaurant", "Bar", "Airport shuttle"])
    return sorted(a)


def make_reviews(tags, amenities, setting, breakfast_idx, lang):
    keys = ["staff", "clean", "location", "value"]
    keys.append("breakfast_good" if breakfast_idx in (0, 2, 5) else "breakfast_bad")
    if "nightlife" in tags:
        keys.append("noise")
    if "quiet" in tags:
        keys.append("quiet")
    if "remote-work" in tags:
        keys.append("wifi_good")
    elif rnd.random() < 0.15:
        keys.append("wifi_bad")
    if "view" in tags or "Sea view" in amenities or "Bosphorus view" in amenities:
        keys.append("view")
    if "family" in tags:
        keys.append("kids")
    if "Pet friendly" in amenities:
        keys.append("pets")
    if "romantic" in tags:
        keys.append("romantic")
    if setting == "cave":
        keys.append("balloons")
    if "ski" in tags:
        keys.append("ski")
    if "Spa" in amenities:
        keys.append("spa")
    # specific signals first, then generic ones
    specific = [k for k in keys if k not in ("staff", "clean", "location", "value")]
    rnd.shuffle(specific)
    chosen = (specific + rnd.sample(["staff", "clean", "location", "value"], 4))[:3]
    out = []
    for k in chosen:
        who, country = rnd.choice(NAMES)
        text = rnd.choice(REVIEW_BANK[k])
        score = {"noise": 7, "wifi_bad": 6, "breakfast_bad": 7}.get(k, rnd.choice([8, 9, 9, 10]))
        if k == "value" and "Overpriced" in text:
            score = 6
        out.append({"author": who, "country": country, "rating": score, "text": text,
                    "date": f"2026-{rnd.randint(1, 9):02d}-{rnd.randint(1, 28):02d}"})
    return out


def jitter(lat, lon, km):
    r = km / 111.0 * math.sqrt(rnd.random())
    a = rnd.random() * 2 * math.pi
    return round(lat + r * math.sin(a), 5), round(lon + r * math.cos(a) / math.cos(math.radians(lat)), 5)


# --------------------------------------------------------------------------- generate
hotels = []
used_names = set()
for (key, city, district, region, lat, lon, setting, count, roots, landmarks) in D:
    types = [t for t, (_, _, settings) in TYPES.items() if setting in settings]
    for i in range(count):
        typ = rnd.choice(types)
        if setting == "cave" and rnd.random() < 0.6:
            typ = "Cave Hotel"
        if setting == "sea" and key in ("lara", "alanya") and rnd.random() < 0.5:
            typ = "Resort"
        lo, hi = TYPES[typ][0]
        stars = rnd.randint(lo, hi)
        for _ in range(50):
            root = rnd.choice(roots)
            word = rnd.choice(TYPE_WORDS[typ])
            name = f"{root} {word}" if rnd.random() < 0.6 else f"{root} {rnd.choice(NAME_EXTRA)} {word}"
            if name not in used_names:
                break
        used_names.add(name)
        tags = pick_tags(setting, typ)
        amen = pick_amenities(setting, typ, tags, stars)
        lang = "tr" if rnd.random() < 0.2 else "en"
        rooms = {"Hostel": rnd.randint(8, 30), "Resort": rnd.randint(180, 600), "Business Hotel": rnd.randint(120, 320),
                 "Villa": rnd.randint(3, 12), "Waterfront Mansion": rnd.randint(6, 18)}.get(typ, rnd.randint(6, 40))
        opener = rnd.choice((OPENERS_TR if lang == "tr" else OPENERS_EN)[typ]).format(n=rooms)
        lm_name, lo_m, hi_m = rnd.choice(landmarks)
        dist = rnd.randint(lo_m, hi_m)
        dist_txt = f"{dist} m" if dist < 1000 else f"{dist / 1000:.1f} km"
        if lang == "tr":
            loc = f"{district}, {city} içinde; {lm_name} yaklaşık {dist_txt} uzaklıkta."
        else:
            loc = f"Located in {district}, {city}, about {dist_txt} from {lm_name}."
        bank = TAG_TR if lang == "tr" else TAG_EN
        tag_sents = [rnd.choice(bank[t]) for t in rnd.sample(tags, k=min(3, len(tags)))]
        bidx = rnd.randrange(len(BREAKFAST_EN))
        if "Breakfast included" not in amen:
            bidx = 4
        breakfast = (BREAKFAST_TR if lang == "tr" else BREAKFAST_EN)[bidx]
        description = " ".join([opener, loc] + tag_sents + [breakfast])
        # keep structured data consistent with the prose
        if re.search(r"\bpool|havuz", description, re.I) and not {"Pool", "Indoor pool"} & set(amen):
            amen = sorted(set(amen) | {"Pool"})
        if re.search(r"hot tub|jakuzi", description, re.I):
            amen = sorted(set(amen) | {"Hot tub"})
        if re.search(r"hammam|hamam", description, re.I):
            amen = sorted(set(amen) | {"Hammam"})
        if re.search(r"\bspa\b", description, re.I):
            amen = sorted(set(amen) | {"Spa"})
        if re.search(r"co-working|ortak çalışma|shared workspace", description, re.I):
            amen = sorted(set(amen) | {"Co-working space"})
        if re.search(r"\bspa\b|thermal|wellness", name, re.I):
            amen = sorted(set(amen) | {"Spa", "Hammam"})
        if re.search(r"fireplace|şömine", description, re.I):
            amen = sorted(set(amen) | {"Fireplace"})

        base = TYPES[typ][1]
        city_mult = {"Istanbul": 1.15, "Muğla": 1.25, "İzmir": 1.1, "Antalya": 1.0, "Nevşehir": 1.05}.get(city, 0.85)
        price = base * city_mult * (0.75 + 0.12 * stars) * rnd.uniform(0.8, 1.25)
        if "luxury" in tags:
            price *= 1.35
        if "budget" in tags:
            price *= 0.7
        price = int(round(price / 5.0) * 5) or 20
        rating = round(min(9.8, max(6.4, rnd.gauss(8.3 + 0.15 * (stars - 3), 0.6))), 1)
        hlat, hlon = jitter(lat, lon, 1.2 if setting in ("historic", "city", "bosphorus", "business") else 3.0)
        reviews = make_reviews(tags, amen, setting, bidx, lang)
        hotels.append({
            "_id": slug(f"{district}-{name}"),
            "name": name,
            "type": typ,
            "city": city,
            "district": district,
            "region": region,
            "country": "Türkiye",
            "setting": setting,
            "location": {"type": "Point", "coordinates": [hlon, hlat]},
            "stars": stars,
            "rating": rating,
            "reviewCount": rnd.randint(12, 2400),
            "pricePerNight": price,
            "currency": "EUR",
            "rooms": rooms,
            "amenities": amen,
            "tags": tags,
            "language": lang,
            "description": description,
            "nearby": [f"{lm} · {(rnd.randint(a, b))} m" if b < 1000 else f"{lm} · {rnd.randint(a, b) / 1000:.1f} km" for lm, a, b in landmarks[:3]],
            "reviews": reviews,
            "art": {"seed": rnd.randint(1, 10_000), "time": rnd.choice(["dawn", "day", "day", "sunset", "sunset", "night"])},
        })

rnd.shuffle(hotels)  # insertion order is "random", like real data arriving over time


def profile(h):
    """The single text field that Atlas Automated Embedding turns into a vector."""
    parts = [
        f"{h['name']}. {h['type']} in {h['district']}, {h['city']} ({h['region']}, Türkiye).",
        h["description"],
        f"Amenities: {', '.join(h['amenities'])}.",
        f"Good for: {', '.join(t.replace('-', ' ') for t in h['tags'])}.",
        f"{h['stars']}-star, guest rating {h['rating']}/10, from {h['pricePerNight']} EUR per night.",
        "Guests say: " + " ".join(r["text"] for r in h["reviews"]),
    ]
    return " ".join(parts)


for h in hotels:
    h["profile"] = profile(h)

OUT.parent.mkdir(parents=True, exist_ok=True)
OUT.write_text(json.dumps(hotels, ensure_ascii=False, indent=1), encoding="utf-8")
print(f"wrote {len(hotels)} hotels to {OUT}")
