<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'

/**
 * 订单数（柱）+ 成交额（线）双轴图。刻度取「好看的数」；
 * 字号按 SVG 实际缩放反推，保证渲染后不小于 12px（与原型看板一致）。
 */
const props = defineProps<{ dates: string[]; bars: number[]; line: number[] }>()
const box = ref<HTMLElement>()
const width = ref(720)
const W = 720
const TICKS = 4

function niceAxis(max: number): [number, number] {
  if (max <= 0) return [TICKS, 1]
  const raw = max / TICKS
  const mag = 10 ** Math.floor(Math.log10(raw))
  const step = [1, 2, 2.5, 3, 4, 5, 10].map((m) => m * mag).find((v) => v >= raw)!
  return [step * TICKS, step]
}
const fmtK = (v: number) => (v === 0 ? '0' : v >= 1000 ? `${+(v / 1000).toFixed(1)}k` : String(v))

const g = computed(() => {
  const fs = Math.max(12, (12 * W) / Math.max(width.value, 1))
  const L = Math.max(40, fs * 2.8), R = Math.max(52, fs * 3.2), T = 16, B = fs * 2.4
  const H = 230 + B, iw = W - L - R, ih = H - T - B, n = Math.max(props.dates.length, 1), bw = iw / n
  const [oMax, oStep] = niceAxis(Math.max(0, ...props.bars))
  const [gMax, gStep] = niceAxis(Math.max(0, ...props.line))
  const x = (i: number) => L + bw * i + bw / 2
  const yo = (v: number) => T + ih - (v / oMax) * ih
  const yg = (v: number) => T + ih - (v / gMax) * ih
  const every = Math.max(1, Math.ceil((fs * 3.2) / bw))
  return { fs, L, R, T, H, ih, bw, oStep, gStep, x, yo, yg, every }
})

let ro: ResizeObserver | undefined
onMounted(() => {
  ro = new ResizeObserver(([e]) => (width.value = e.contentRect.width))
  if (box.value) ro.observe(box.value)
})
onBeforeUnmount(() => ro?.disconnect())
</script>

<template>
  <div ref="box" role="img" :aria-label="`近 ${dates.length} 天订单数与成交额趋势`">
    <svg :viewBox="`0 0 ${W} ${g.H}`" style="width: 100%; height: auto" :font-size="g.fs" fill="var(--text-3)">
      <g v-for="k in TICKS + 1" :key="k">
        <line :x1="g.L" :x2="W - g.R" :y1="g.T + g.ih - ((k - 1) / TICKS) * g.ih" :y2="g.T + g.ih - ((k - 1) / TICKS) * g.ih" stroke="#EEF2F0" />
        <text :x="g.L - 8" :y="g.T + g.ih - ((k - 1) / TICKS) * g.ih + g.fs / 3" text-anchor="end">{{ g.oStep * (k - 1) }}</text>
        <text :x="W - g.R + 8" :y="g.T + g.ih - ((k - 1) / TICKS) * g.ih + g.fs / 3">{{ fmtK(g.gStep * (k - 1)) }}</text>
      </g>
      <rect v-for="(v, i) in bars" :key="`b${i}`" :x="g.x(i) - g.bw * 0.3" :y="g.yo(v)" :width="g.bw * 0.6" :height="g.T + g.ih - g.yo(v)" rx="3" fill="#A7F3D0">
        <title>{{ dates[i] }} 订单 {{ v }}</title>
      </rect>
      <polyline fill="none" stroke="#C2410C" stroke-width="2" :points="line.map((v, i) => `${g.x(i)},${g.yg(v)}`).join(' ')" />
      <circle v-for="(v, i) in line" :key="`c${i}`" :cx="g.x(i)" :cy="g.yg(v)" r="3" fill="#fff" stroke="#C2410C" stroke-width="2"><title>{{ dates[i] }} 成交额 ¥{{ v }}</title></circle>
      <template v-for="(d, i) in dates" :key="`d${i}`">
        <text v-if="(dates.length - 1 - i) % g.every === 0" :x="g.x(i)" :y="g.H - g.fs * 0.8" text-anchor="middle">{{ d.slice(5) }}</text>
      </template>
    </svg>
  </div>
</template>
