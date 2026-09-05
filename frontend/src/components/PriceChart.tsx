import { useEffect, useRef } from "react";
import {
  createChart,
  type IChartApi,
  type ISeriesApi,
  type CandlestickData,
  ColorType,
  CrosshairMode,
} from "lightweight-charts";

export type Candle = {
  time: string; // ISO
  open: number;
  high: number;
  low: number;
  close: number;
};

type Drawing = {
  type: string;
  price?: number;
  levels?: { level: number; price: number }[];
  kind?: string;
  style?: { color?: string };
};

type Props = {
  candles: Candle[];
  drawings?: Drawing[];
};

function toChartTime(iso: string): number {
  // lightweight-charts expects UTCTimestamp (seconds)
  return Math.floor(new Date(iso).getTime() / 1000);
}

export default function PriceChart({ candles, drawings = [] }: Props) {
  const containerRef = useRef<HTMLDivElement>(null);
  const chartRef = useRef<IChartApi | null>(null);
  const seriesRef = useRef<ISeriesApi<"Candlestick"> | null>(null);

  useEffect(() => {
    if (!containerRef.current) return;

    const chart = createChart(containerRef.current, {
      layout: {
        background: { type: ColorType.Solid, color: "#0b0e11" },
        textColor: "#8b9aab",
      },
      grid: {
        vertLines: { color: "#1e2530" },
        horzLines: { color: "#1e2530" },
      },
      crosshair: { mode: CrosshairMode.Normal },
      rightPriceScale: { borderColor: "#1e2530" },
      timeScale: { borderColor: "#1e2530", timeVisible: true, secondsVisible: false },
      autoSize: true,
    });

    const series = chart.addCandlestickSeries({
      upColor: "#22c55e",
      downColor: "#ef4444",
      borderUpColor: "#22c55e",
      borderDownColor: "#ef4444",
      wickUpColor: "#22c55e",
      wickDownColor: "#ef4444",
    });

    chartRef.current = chart;
    seriesRef.current = series;

    const ro = new ResizeObserver(() => {
      if (containerRef.current) {
        chart.applyOptions({
          width: containerRef.current.clientWidth,
          height: containerRef.current.clientHeight,
        });
      }
    });
    ro.observe(containerRef.current);

    return () => {
      ro.disconnect();
      chart.remove();
      chartRef.current = null;
      seriesRef.current = null;
    };
  }, []);

  useEffect(() => {
    if (!seriesRef.current || !candles.length) return;
    const data: CandlestickData[] = candles
      .map((c) => ({
        time: toChartTime(c.time) as any,
        open: c.open,
        high: c.high,
        low: c.low,
        close: c.close,
      }))
      .sort((a, b) => (a.time as number) - (b.time as number));

    // dedupe times
    const map = new Map<number, CandlestickData>();
    for (const d of data) map.set(d.time as number, d);
    seriesRef.current.setData([...map.values()]);
    chartRef.current?.timeScale().fitContent();
  }, [candles]);

  useEffect(() => {
    const series = seriesRef.current;
    if (!series) return;
    // 水平线：支撑压力
    const lines: { price: number; color: string; title: string }[] = [];
    for (const d of drawings) {
      if (d.type === "horizontal" && d.price != null) {
        lines.push({
          price: d.price,
          color: d.style?.color || (d.kind === "support" ? "#22c55e" : "#ef4444"),
          title: d.kind || "line",
        });
      }
      if (d.type === "fibonacci" && d.levels) {
        for (const lv of d.levels) {
          lines.push({
            price: lv.price,
            color: d.style?.color || "#3b82f6",
            title: `fib ${lv.level}`,
          });
        }
      }
    }
    // lightweight-charts v4: createPriceLine
    // 先清掉旧线较难，这里简单：每次重建会在 series 生命周期内叠加；限制数量
    const existing = (series as any)._priceLines as any[] | undefined;
    if (existing) {
      for (const pl of [...existing]) {
        try {
          series.removePriceLine(pl);
        } catch {
          /* ignore */
        }
      }
    }
    for (const l of lines.slice(0, 20)) {
      series.createPriceLine({
        price: l.price,
        color: l.color,
        lineWidth: 1,
        lineStyle: 2,
        axisLabelVisible: true,
        title: l.title,
      });
    }
  }, [drawings]);

  return (
    <div className="chart-wrap">
      <div ref={containerRef} style={{ width: "100%", height: "100%" }} />
      {!candles.length && (
        <div className="empty-chart">暂无 K 线数据，请先启动 Data Service 并采集</div>
      )}
    </div>
  );
}
