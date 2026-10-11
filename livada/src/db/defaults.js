// Date implicite ale magazinului. Datele firmei sunt PLACEHOLDER — se înlocuiesc din Admin → Setări.

export const DEFAULT_SETTINGS = {
  company: {
    brand: 'Livada',
    legal_name: 'Livada Digital S.R.L.',
    cui: 'RO48731906',
    reg_com: 'J40/18245/2023',
    euid: 'ROONRC.J40/18245/2023',
    capital: '10.000 lei',
    address: 'Str. Polonă nr. 68, et. 2, Sector 1, București 010506',
    phone: '+40 31 630 2741',
    phone_hours: 'Luni–Vineri 09:00–19:00, Sâmbătă 10:00–16:00',
    email: 'contact@livada.ro',
    orders_email: 'comenzi@livada.ro',
    iban: 'RO49 AAAA 1B31 0075 9384 0000',
    bank: 'Banca Transilvania',
    instagram: 'https://instagram.com/livada.ro',
    facebook: 'https://facebook.com/livada.ro',
    tiktok: 'https://tiktok.com/@livada.ro',
  },
  showroom: {
    name: 'Showroom Livada Dorobanți',
    address: 'Calea Dorobanți nr. 142, Sector 1, București 010581',
    hours: 'Luni–Vineri 10:00–20:00 · Sâmbătă 10:00–18:00 · Duminică închis',
    lat: 44.4598, lng: 26.0956,
  },
  store: {
    vat: 21,
    cod_fee: 999,            // taxă ramburs (bani)
    cod_max: 1500000,        // ramburs permis până la 15.000 lei
    return_days: 30,
    warranty_months: 24,
    order_prefix: 'LV',
    cutoff_hour: 16,         // comenzile până la această oră pleacă în aceeași zi
    express_cutoff_hour: 14,
    installments_min: 50000, // rate disponibile de la 500 lei
    announcement: ['Livrare gratuită de la 500 lei', 'easybox și FANbox în 24 de ore', 'Retur gratuit 30 de zile', 'Rate 0% până la 12 luni'],
  },
};

const lei = (v) => Math.round(v * 100);

export const DELIVERY_METHODS = [
  // curier la adresă
  { code: 'courier_fan', type: 'courier', carrier: 'fan', name: 'FAN Courier', description: 'Livrare la adresă, cu notificare SMS și urmărire AWB.', eta: '24 de ore', price: lei(19.99), free_over: lei(500), cod_allowed: 1, sort: 10 },
  { code: 'courier_sameday', type: 'courier', carrier: 'sameday', name: 'Sameday', description: 'Livrare la adresă, interval orar estimat în aplicația Sameday.', eta: '24 de ore', price: lei(18.99), free_over: lei(500), cod_allowed: 1, sort: 11 },
  { code: 'courier_cargus', type: 'courier', carrier: 'cargus', name: 'Cargus', description: 'Livrare la adresă în toată țara.', eta: '24–48 de ore', price: lei(17.99), free_over: lei(500), cod_allowed: 1, sort: 12 },
  { code: 'courier_dpd', type: 'courier', carrier: 'dpd', name: 'DPD', description: 'Livrare Predict: fereastră de o oră anunțată dimineața.', eta: '24–48 de ore', price: lei(18.49), free_over: lei(500), cod_allowed: 1, sort: 13 },
  { code: 'courier_gls', type: 'courier', carrier: 'gls', name: 'GLS', description: 'Livrare la adresă cu FlexDelivery.', eta: '24–48 de ore', price: lei(18.99), free_over: lei(500), cod_allowed: 1, sort: 14 },
  { code: 'courier_posta', type: 'courier', carrier: 'posta', name: 'Poșta Română', description: 'Colet standard, inclusiv în localitățile fără acoperire de curier.', eta: '2–5 zile lucrătoare', price: lei(14.99), free_over: lei(500), cod_allowed: 1, sort: 15 },
  // aceeași zi
  { code: 'express_bucuresti', type: 'express', carrier: 'sameday', name: 'Livrare în aceeași zi', description: 'București și Ilfov — pentru comenzile plasate până la ora 14:00.', eta: 'Azi, 18:00–22:00', price: lei(39.99), free_over: null, cod_allowed: 1, sort: 20 },
  // lockere
  { code: 'locker_easybox', type: 'locker', carrier: 'easybox', name: 'easybox', description: 'Lockerele Sameday — plată cu cardul la locker pentru ramburs.', eta: '24 de ore', price: lei(12.99), free_over: lei(300), cod_allowed: 1, sort: 30 },
  { code: 'locker_fanbox', type: 'locker', carrier: 'fanbox', name: 'FANbox', description: 'Lockerele FAN Courier, deschise non-stop.', eta: '24 de ore', price: lei(12.99), free_over: lei(300), cod_allowed: 1, sort: 31 },
  { code: 'locker_cargus', type: 'locker', carrier: 'cargus', name: 'Cargus Ship & Go', description: 'Lockere și puncte Ship & Go.', eta: '24–48 de ore', price: lei(11.99), free_over: lei(300), cod_allowed: 0, sort: 32 },
  { code: 'locker_dpd', type: 'locker', carrier: 'dpd', name: 'DPD Box', description: 'Lockere și puncte DPD Pickup.', eta: '24–48 de ore', price: lei(11.99), free_over: lei(300), cod_allowed: 0, sort: 33 },
  { code: 'locker_gls', type: 'locker', carrier: 'gls', name: 'GLS ParcelLocker', description: 'Lockere GLS și ParcelShop.', eta: '24–48 de ore', price: lei(11.99), free_over: lei(300), cod_allowed: 0, sort: 34 },
  { code: 'locker_packeta', type: 'locker', carrier: 'packeta', name: 'Packeta Z-BOX', description: 'Z-BOX și puncte Packeta.', eta: '2–3 zile', price: lei(10.99), free_over: lei(300), cod_allowed: 0, sort: 35 },
  // ridicare
  { code: 'pickup_showroom', type: 'pickup', carrier: 'livada', name: 'Ridicare din showroom', description: 'Calea Dorobanți 142, București — comanda e gata în aproximativ 2 ore.', eta: 'Azi', price: 0, free_over: null, cod_allowed: 1, sort: 40 },
];

export const CARRIERS = {
  fan: { name: 'FAN Courier', short: 'FAN', color: '#0B4EA2', ink: '#FFD200', track: 'https://www.fancourier.ro/awb-tracking/?tracking={awb}' },
  sameday: { name: 'Sameday', short: 'SD', color: '#E10A1E', ink: '#FFFFFF', track: 'https://sameday.ro/#awb={awb}' },
  cargus: { name: 'Cargus', short: 'CG', color: '#F26B21', ink: '#FFFFFF', track: 'https://www.cargus.ro/personal/urmareste-coletul/?tracking_number={awb}' },
  dpd: { name: 'DPD', short: 'DPD', color: '#DC0032', ink: '#FFFFFF', track: 'https://tracking.dpd.ro/?shipmentNumber={awb}' },
  gls: { name: 'GLS', short: 'GLS', color: '#061AB1', ink: '#FFD100', track: 'https://gls-group.com/RO/ro/urmarire-colet?match={awb}' },
  posta: { name: 'Poșta Română', short: 'PR', color: '#003F87', ink: '#FFCC00', track: 'https://www.posta-romana.ro/track-trace.html?id={awb}' },
  easybox: { name: 'easybox', short: 'eb', color: '#E10A1E', ink: '#FFFFFF', track: 'https://sameday.ro/#awb={awb}' },
  fanbox: { name: 'FANbox', short: 'FB', color: '#0B4EA2', ink: '#FFD200', track: 'https://www.fancourier.ro/awb-tracking/?tracking={awb}' },
  packeta: { name: 'Packeta', short: 'PK', color: '#BA1B02', ink: '#FFFFFF', track: 'https://tracking.packeta.com/ro/?id={awb}' },
  livada: { name: 'Livada', short: 'LV', color: '#0E1411', ink: '#8EE0AE', track: '' },
};

export const PROMO_CODES = [
  { code: 'BUNVENIT50', description: '50 lei reducere la prima comandă de minimum 500 lei', type: 'fixed', value: lei(50), min_subtotal: lei(500), max_discount: null, max_uses: null },
  { code: 'LIVADA10', description: '10% reducere, maximum 300 lei, la comenzi de minimum 200 lei', type: 'percent', value: 10, min_subtotal: lei(200), max_discount: lei(300), max_uses: 500 },
  { code: 'TRANSPORT0', description: 'Transport gratuit la orice metodă de livrare', type: 'shipping', value: 0, min_subtotal: 0, max_discount: null, max_uses: null },
  { code: 'VARA2026', description: 'Campania de vară (expirată)', type: 'percent', value: 8, min_subtotal: 0, max_discount: lei(400), max_uses: null, ends_at: '2026-08-31 21:00:00' },
];

export const COUNTIES = ['Alba', 'Arad', 'Argeș', 'Bacău', 'Bihor', 'Bistrița-Năsăud', 'Botoșani', 'Brașov', 'Brăila', 'București',
  'Buzău', 'Caraș-Severin', 'Călărași', 'Cluj', 'Constanța', 'Covasna', 'Dâmbovița', 'Dolj', 'Galați', 'Giurgiu', 'Gorj',
  'Harghita', 'Hunedoara', 'Ialomița', 'Iași', 'Ilfov', 'Maramureș', 'Mehedinți', 'Mureș', 'Neamț', 'Olt', 'Prahova',
  'Satu Mare', 'Sălaj', 'Sibiu', 'Suceava', 'Teleorman', 'Timiș', 'Tulcea', 'Vaslui', 'Vâlcea', 'Vrancea'];
