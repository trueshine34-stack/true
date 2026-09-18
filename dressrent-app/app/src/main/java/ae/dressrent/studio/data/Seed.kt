package ae.dressrent.studio.data

import java.time.LocalDate

/**
 * Demo content for trying the app out before real inventory is entered. Nothing is seeded
 * automatically — an empty studio must show empty numbers, not invented ones.
 */
object Seed {

    suspend fun fill(repo: Repository) {
        val today = LocalDate.now()

        val items = listOf(
            Item(title = "Silk Gown «Amal»", brand = "Elie Saab", category = Category.EVENING, size = "S", color = "Шампань", pricePerDay = 65_000, deposit = 100_000, purchaseCost = 1_200_000),
            Item(title = "Sequin Midi «Layla»", brand = "Zuhair Murad", category = Category.COCKTAIL, size = "M", color = "Изумруд", pricePerDay = 38_000, deposit = 60_000, purchaseCost = 620_000),
            Item(title = "Velvet Cape Dress", brand = "Cavalli", category = Category.EVENING, size = "M", color = "Чёрный", pricePerDay = 55_000, deposit = 80_000, purchaseCost = 900_000),
            Item(title = "Pearl Abaya", brand = "Local Atelier", category = Category.ABAYA, size = "L", color = "Молочный", pricePerDay = 30_000, deposit = 50_000, purchaseCost = 400_000),
            Item(title = "Crystal Clutch", brand = "Jimmy Choo", category = Category.ACCESSORY, size = "—", color = "Серебро", pricePerDay = 9_000, deposit = 30_000, purchaseCost = 180_000)
        ).map { it.copy(id = repo.items.insert(it)) }

        val clients = listOf(
            Client(name = "Алина", phone = "971501112233", source = LeadSource.INSTAGRAM),
            Client(name = "Мария", phone = "971502223344", source = LeadSource.REFERRAL),
            Client(name = "Сара", phone = "971503334455", source = LeadSource.WEBSITE),
            Client(name = "Лейла", phone = "971504445566", source = LeadSource.TIKTOK)
        ).map { it.copy(id = repo.clients.insert(it)) }

        // Finished last week — generates a review task.
        val done = Booking(
            itemId = items[1].id, clientId = clients[3].id,
            startDate = today.minusDays(5).toEpochDay(), endDate = today.minusDays(4).toEpochDay(),
            eventName = "День рождения", price = 38_000, deposit = 60_000,
            status = BookingStatus.RETURNED
        )
        val doneId = repo.bookings.insert(done)
        repo.payments.insert(Payment(bookingId = doneId, amount = 38_000, date = today.minusDays(5).toEpochDay(), kind = PaymentKind.BALANCE))

        // Out right now, due back today — generates a return task.
        val out = Booking(
            itemId = items[0].id, clientId = clients[0].id,
            startDate = today.minusDays(1).toEpochDay(), endDate = today.toEpochDay(),
            eventName = "Гала-ужин", price = 65_000, deposit = 100_000,
            status = BookingStatus.OUT
        )
        val outId = repo.bookings.insert(out)
        repo.payments.insert(Payment(bookingId = outId, amount = 65_000, date = today.minusDays(1).toEpochDay(), kind = PaymentKind.PREPAY))
        repo.payments.insert(Payment(bookingId = outId, amount = 100_000, date = today.minusDays(1).toEpochDay(), kind = PaymentKind.DEPOSIT))

        // Confirmed, half paid, handed over today — generates a "collect the balance" task.
        val pickup = Booking(
            itemId = items[2].id, clientId = clients[1].id,
            startDate = today.toEpochDay(), endDate = today.plusDays(1).toEpochDay(),
            eventName = "Свадьба подруги", price = 55_000, deposit = 80_000,
            status = BookingStatus.CONFIRMED
        )
        val pickupId = repo.bookings.insert(pickup)
        repo.payments.insert(Payment(bookingId = pickupId, amount = 20_000, date = today.minusDays(2).toEpochDay(), kind = PaymentKind.PREPAY))

        // An unanswered request from three days ago — the follow-up that usually pays for the day.
        repo.bookings.insert(
            Booking(
                itemId = items[3].id, clientId = clients[2].id,
                startDate = today.plusDays(4).toEpochDay(), endDate = today.plusDays(5).toEpochDay(),
                eventName = "Корпоратив", price = 30_000, deposit = 50_000,
                status = BookingStatus.LEAD,
                createdAt = System.currentTimeMillis() - 3L * 24 * 60 * 60 * 1000
            )
        )

        repo.settingsStore.markSeeded()
    }

    suspend fun clear(repo: Repository) = repo.clearAll()
}
