import { chartPercents, hasChartData } from '../../report/decimalScale.ts';

export interface BarChartBar {
  label: string;
  value: string;
}

export function BarChart({
  title,
  bars,
  empty,
}: {
  title: string;
  bars: readonly BarChartBar[];
  empty: string;
}) {
  const values = bars.map((bar) => bar.value);
  if (!hasChartData(values)) {
    return <p className="memory-empty" role="status">{empty}</p>;
  }
  const percents = chartPercents(values);
  const height = Math.max(120, bars.length * 36);
  return (
    <figure className="report-chart">
      <figcaption>{title}</figcaption>
      <svg
        className="report-chart-svg"
        viewBox={`0 0 640 ${height}`}
        role="img"
        aria-label={title}
      >
        {bars.map((bar, index) => {
          const y = 8 + index * 36;
          const width = Math.max(percents[index] ?? 0, 0) * 4;
          const display = bar.value === '' ? '—' : bar.value;
          return (
            <g key={bar.label}>
              <text x="0" y={y + 16} className="report-chart-svg-label">{bar.label}</text>
              <rect x="120" y={y} width="500" height="22" rx="4" className="report-chart-svg-track" />
              <rect x="120" y={y} width={width} height="22" rx="4" className="report-chart-svg-bar" />
              <text x="630" y={y + 16} className="report-chart-svg-value" textAnchor="end">{display}</text>
            </g>
          );
        })}
      </svg>
    </figure>
  );
}
