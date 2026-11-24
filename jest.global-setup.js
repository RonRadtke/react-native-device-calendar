/** Every test runs in Europe/Berlin, whose change to and from summer time the tests of all-day events cross */
module.exports = () => {
  process.env.TZ = 'Europe/Berlin';
};
