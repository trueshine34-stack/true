/**
 * Zamiana naszego ogloszenia na payload OLX i lokalna walidacja.
 * Czysty modul - identyczny na serwerze i w aplikacji.
 */

/**
 * @param {object} listing ogloszenie po formatowaniu (ma .formatted i .priced)
 * @param {object} settings ustawienia panelu
 */
export function toAdvertPayload(listing, settings) {
  const { formatted = {}, priced = {} } = listing;
  const location = { ...settings.location, ...listing.location };
  const contact = { ...settings.contact, ...listing.contact };

  const payload = {
    title: formatted.title || listing.title,
    description: formatted.description || listing.description,
    category_id: Number(listing.olxCategoryId || listing.categoryId),
    advertiser_type: listing.advertiserType || settings.advertiserType || 'private',
    external_id: listing.id,
    contact: { name: contact.name || undefined, phone: contact.phone || undefined },
    location: {
      city_id: location.cityId ? Number(location.cityId) : undefined,
      district_id: location.districtId ? Number(location.districtId) : undefined,
      latitude: location.lat ?? undefined,
      longitude: location.lon ?? undefined,
    },
    images: (listing.images || []).map((url) => ({ url })),
    price: {
      value: Number(priced.price ?? listing.basePrice ?? 0),
      currency: priced.currency || 'PLN',
      negotiable: priced.negotiable !== false,
      trade: false,
      budget: false,
    },
    attributes: Object.entries(listing.olxAttributes || {})
      .filter(([, v]) => v !== '' && v !== null && v !== undefined)
      .map(([code, value]) => (Array.isArray(value) ? { code, values: value } : { code, value: String(value) })),
  };

  if (!payload.contact.name) delete payload.contact.name;
  if (!payload.contact.phone) delete payload.contact.phone;
  if (!Object.keys(payload.contact).length) delete payload.contact;
  for (const key of Object.keys(payload.location)) {
    if (payload.location[key] === undefined) delete payload.location[key];
  }
  return payload;
}

/** Sprawdza payload lokalnie - taniej niz dostac 400 z API. */
export function validatePayload(payload) {
  const errors = [];
  if (!payload.title || payload.title.length < 6) errors.push('Tytul musi mieć min. 6 znaków');
  if (payload.title && payload.title.length > 70) errors.push('Tytul dłuższy niż 70 znaków');
  if (!payload.description || payload.description.length < 30) errors.push('Opis musi mieć min. 30 znaków');
  if (!payload.category_id) errors.push('Brak category_id (kategoria OLX)');
  if (!payload.location?.city_id) errors.push('Brak city_id (miasto)');
  if (!payload.images?.length) errors.push('Brak zdjęć');
  if (!(payload.price?.value > 0)) errors.push('Cena musi być większa od 0');
  return errors;
}
