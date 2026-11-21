/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

import { useEffect, useRef } from 'react';
import * as echarts from 'echarts';
import { getAssistantActionService, ChartPilotRenderProps } from './assistant_action_service_locator';

interface ChartArgs {
  chart_type?: string;
  title?: string;
  data?: Array<{ name: string; value: number }>;
}

/**
 * Богатые графики в чате через стороннюю библиотеку (ECharts):
 * штатный graph_timeseries_data умеет только линии, а этот action рендерит
 * bar/pie/line по категориальным данным. Регистрируется через глобальный
 * singleton AssistantActionService — доступен любому OSD-плагину.
 */
export function registerChartPilotChartAction(): void {
  getAssistantActionService().registerAction({
    name: 'chart_pilot_chart',
    description: 'Render bar/pie/line chart from categorical data (ChartPilot)',
    parameters: {
      type: 'object',
      properties: {
        chart_type: { type: 'string', enum: ['bar', 'pie', 'line'] },
        title: { type: 'string' },
        data: { type: 'array', items: { type: 'object' } },
      },
      required: ['data'],
    },
    handler: async (args: ChartArgs) => ({
      success: true,
      chartPilotChart: true,
      points: args.data?.length ?? 0,
    }),
    useCustomRenderer: true,
    renderPlacement: 'below',
    render: ({ args, status }: ChartPilotRenderProps) => (
      <ChartPilotChart args={args as ChartArgs | undefined} status={status} />
    ),
  });
}

function ChartPilotChart({ args, status }: { args?: ChartArgs; status: string }) {
  const ref = useRef<HTMLDivElement>(null);
  const type = args?.chart_type === 'pie' ? 'pie' : args?.chart_type === 'line' ? 'line' : 'bar';

  useEffect(() => {
    if (!ref.current || !args?.data || args.data.length === 0) {
      return;
    }
    const chart = echarts.init(ref.current);
    const option: echarts.EChartsOption =
      type === 'pie'
        ? {
            title: { text: args.title ?? '', left: 'center', textStyle: { fontSize: 13 } },
            tooltip: { trigger: 'item' },
            series: [
              {
                type: 'pie',
                radius: ['30%', '65%'],
                data: args.data.map((d) => ({ name: d.name, value: d.value })),
              },
            ],
          }
        : {
            title: { text: args.title ?? '', left: 'center', textStyle: { fontSize: 13 } },
            tooltip: { trigger: 'axis' },
            grid: { left: 48, right: 16, bottom: 40, top: 40 },
            xAxis: { type: 'category', data: args.data.map((d) => d.name) },
            yAxis: {
              type: 'value',
              // компактные подписи: полные числа (50 000) не помещаются в узкую панель чата
              axisLabel: { formatter: (value: number) => (Math.abs(value) >= 1000 ? `${value / 1000}K` : `${value}`) },
            },
            series: [{ type, data: args.data.map((d) => d.value) }],
          };
    chart.setOption(option);
    const onResize = () => chart.resize();
    window.addEventListener('resize', onResize);
    return () => {
      window.removeEventListener('resize', onResize);
      chart.dispose();
    };
  }, [args]);

  if (status === 'failed') {
    return <div style={{ padding: 8, color: '#bd271e' }}>Chart rendering failed</div>;
  }
  // data-chart-type — маркер для E2E: тип графика проверяется в DOM, не по скриншоту
  return <div ref={ref} data-chart-type={type} style={{ width: '100%', height: 300 }} />;
}