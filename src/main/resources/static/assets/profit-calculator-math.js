// All inputs are parsed as integer paise; midpoint half-paise values are rounded only for display.
export const MAX_AMOUNT = 9999999.99;
export const MAX_DAYS = 36500;
export function validateInputs(values) {
  const errors = {}, parsed = {};
  for (const key of ['price', 'minimum', 'maximum']) {
    const value = String(values[key] ?? '').trim();
    if (!value) errors[key] = 'Enter an amount in rupees.';
    else if (!/^\d+(?:\.\d{1,2})?$/.test(value)) errors[key] = 'Use a non-negative amount with up to two decimal places.';
    else {
      const [whole, fraction = ''] = value.split('.');
      const paise = Number(whole) * 100 + Number(fraction.padEnd(2, '0'));
      if (!Number.isSafeInteger(paise) || paise > Math.round(MAX_AMOUNT * 100)) errors[key] = 'Enter an amount no greater than ₹99,99,999.99.';
      else if (key === 'price' && paise === 0) errors[key] = 'Purchase price must be greater than ₹0.00.';
      else parsed[key] = paise;
    }
  }
  const days = String(values.days ?? '').trim();
  if (!/^\d+$/.test(days) || Number(days) < 1 || Number(days) > MAX_DAYS) errors.days = 'Enter a whole number from 1 to 36,500 days.';
  else parsed.days = Number(days);
  if (parsed.minimum !== undefined && parsed.maximum !== undefined && parsed.maximum < parsed.minimum) errors.maximum = 'Maximum daily income must be at least the minimum.';
  return {errors, value: Object.keys(errors).length ? null : parsed};
}
export function calculateProfit({price, minimum, maximum, days}) {
  const scenarios = [minimum, (minimum + maximum) / 2, maximum].map(daily => {
    const total = daily * days, net = total - price;
    return {daily, total, net, roi: net / price * 100, recovery: total / price * 100,
      breakEven: daily > 0 ? Math.ceil(price / daily) : null, dailyNet: daily - price / days};
  });
  return {price, days, scenarios, downside: Math.max(0, -scenarios[0].net)};
}
