const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const source = fs.readFileSync(path.join(__dirname, '../../main/resources/static/assets/profit-calculator-math.js'), 'utf8');
const math = import('data:text/javascript;base64,' + Buffer.from(source).toString('base64'));
const inputs = {price: '1000', minimum: '20', maximum: '40', days: '30'};
test('range crosses purchase price: midpoint is a loss and break-even is outside term', async () => {
  const {validateInputs, calculateProfit} = await math;
  const result = calculateProfit(validateInputs(inputs).value);
  assert.deepEqual(result.scenarios.map(s => s.total), [60000, 90000, 120000]);
  assert.deepEqual(result.scenarios.map(s => s.net), [-40000, -10000, 20000]);
  assert.deepEqual(result.scenarios.map(s => s.roi), [-40, -10, 20]);
  assert.deepEqual(result.scenarios.map(s => s.breakEven), [50, 34, 25]);
  assert.equal(result.downside, 40000);
});
test('zero income exposes the full price without Infinity or division by zero', async () => {
  const {validateInputs, calculateProfit} = await math;
  const result = calculateProfit(validateInputs({...inputs, minimum: '0', maximum: '0'}).value);
  assert.equal(result.downside, 100000);
  for (const s of result.scenarios) { assert.equal(s.breakEven, null); assert.equal(s.roi, -100); assert.equal(s.recovery, 0); }
});
test('midpoint is not rounded before multiplying by duration', async () => {
  const {validateInputs, calculateProfit} = await math;
  const result = calculateProfit(validateInputs({price: '0.30', minimum: '0.01', maximum: '0.02', days: '30'}).value);
  assert.equal(result.scenarios[1].daily, 1.5);
  assert.equal(result.scenarios[1].total, 45);
  assert.equal(result.scenarios[1].net, 15);
  assert.equal(result.scenarios[1].breakEven, 20);
});
test('exact recovery on final day has zero profit, ROI and downside', async () => {
  const {validateInputs, calculateProfit} = await math;
  const result = calculateProfit(validateInputs({price: '100', minimum: '10', maximum: '10', days: '10'}).value);
  for (const s of result.scenarios) { assert.equal(s.net, 0); assert.equal(s.roi, 0); assert.equal(s.breakEven, 10); assert.equal(s.dailyNet, 0); }
  assert.equal(result.downside, 0);
});
test('invalid amounts and durations never produce results', async () => {
  const {validateInputs} = await math;
  for (const price of ['', '0', '-1', '1.001', '1e3', 'Infinity', 'NaN', '1,000', '10000000', '999999999999999999']) {
    const result = validateInputs({...inputs, price}); assert.equal(result.value, null, price); assert.ok(result.errors.price, price);
  }
  for (const days of ['', '0', '-1', '1.5', '36501', 'NaN']) assert.ok(validateInputs({...inputs, days}).errors.days, days);
  assert.ok(validateInputs({...inputs, minimum: '41'}).errors.maximum);
  assert.ok(validateInputs({...inputs, minimum: '-1'}).errors.minimum);
});
test('upper bounds remain finite and accurately represented', async () => {
  const {validateInputs, calculateProfit} = await math;
  const values = validateInputs({price: '9999999.99', minimum: '9999999.99', maximum: '9999999.99', days: '36500'});
  assert.deepEqual(values.errors, {});
  const result = calculateProfit(values.value);
  assert.equal(result.scenarios[0].total, 999999999 * 36500);
  assert.ok(Number.isSafeInteger(result.scenarios[0].total));
});
