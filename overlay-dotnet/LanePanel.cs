using System;
using System.Collections.Generic;
using System.Globalization;
using System.Windows;
using System.Windows.Media;

namespace LaneOverlay
{
    /// <summary>One lane of the approach: direction it takes (degrees, + = right; null = unknown), whether the route uses it, optional second direction.</summary>
    public sealed record Lane(double? Angle, bool Preferred, double? Alt);

    /// <summary>The lane panel graphic (same design as the tablet / web panel), scaled to fit the control.</summary>
    public sealed class LanePanel : FrameworkElement
    {
        public const double Cell = 50, Pad = 10, PanelH = 112;

        public IReadOnlyList<Lane>? Lanes { get; set; }
        public string Caption { get; set; } = "";
        public string? Message { get; set; }   // shown instead of lanes (placeholder)
        public bool Placeholder { get; set; } = true;

        public static Size DesignSize(int lanes, string caption) =>
            new Size(Math.Max(lanes * Cell + 2 * Pad, caption.Length > 0 ? 80 : 0), PanelH);

        static readonly Brush Back = Freeze(new SolidColorBrush(Color.FromArgb(235, 38, 46, 62)));
        static readonly Brush BackIdle = Freeze(new SolidColorBrush(Color.FromArgb(150, 38, 46, 62)));
        static readonly Brush Green = Freeze(new SolidColorBrush(Color.FromRgb(60, 235, 130)));
        static readonly Brush GreenGlow = Freeze(new SolidColorBrush(Color.FromArgb(80, 60, 235, 130)));
        static readonly Brush White = Freeze(new SolidColorBrush(Color.FromRgb(245, 247, 250)));
        static readonly Brush Faint = Freeze(new SolidColorBrush(Color.FromArgb(95, 255, 255, 255)));
        static readonly Pen Divider = FreezePen(new Pen(new SolidColorBrush(Color.FromArgb(110, 255, 255, 255)), 1.5) { DashStyle = new DashStyle(new double[] { 3.3, 3.3 }, 0) });
        static readonly Typeface Face = new Typeface(new FontFamily("Segoe UI"), FontStyles.Normal, FontWeights.Bold, FontStretches.Normal);

        static Brush Freeze(Brush b) { b.Freeze(); return b; }
        static Pen FreezePen(Pen p) { p.Brush.Freeze(); p.Freeze(); return p; }

        protected override void OnRender(DrawingContext dc)
        {
            double dpi = VisualTreeHelper.GetDpi(this).PixelsPerDip;
            var lanes = Lanes;
            double w, h;
            if (lanes == null || lanes.Count == 0)
            {
                if (!Placeholder) return;
                w = 3 * Cell + 2 * Pad; h = PanelH;
                Begin(dc, w, h);
                dc.DrawRoundedRectangle(BackIdle, null, new Rect(0, 0, w, h), 14, 14);
                var ft = new FormattedText(Message ?? "Waiting for lane data", CultureInfo.InvariantCulture, FlowDirection.LeftToRight,
                    new Typeface("Segoe UI"), 13, new SolidColorBrush(Color.FromArgb(170, 255, 255, 255)), dpi)
                { MaxTextWidth = w - 16, TextAlignment = TextAlignment.Center };
                dc.DrawText(ft, new Point(8, (h - ft.Height) / 2));
                dc.Pop(); dc.Pop();
                return;
            }
            var ds = DesignSize(lanes.Count, Caption);
            w = ds.Width; h = ds.Height;
            Begin(dc, w, h);
            Draw(dc, lanes, Caption, w, h, dpi);
            dc.Pop(); dc.Pop();
        }

        void Begin(DrawingContext dc, double w, double h)
        {
            double s = Math.Min(ActualWidth / w, ActualHeight / h);
            dc.PushTransform(new TranslateTransform((ActualWidth - w * s) / 2, (ActualHeight - h * s) / 2));
            dc.PushTransform(new ScaleTransform(s, s));
        }

        public static void Draw(DrawingContext dc, IReadOnlyList<Lane> lanes, string caption, double w, double h, double dpi)
        {
            dc.DrawRoundedRectangle(Back, null, new Rect(0, 0, w, h), 14, 14);
            if (caption.Length > 0)
            {
                var ft = new FormattedText(caption, CultureInfo.InvariantCulture, FlowDirection.LeftToRight, Face, 14,
                    new SolidColorBrush(Color.FromArgb(230, 255, 255, 255)), dpi);
                dc.DrawText(ft, new Point((w - ft.Width) / 2, h - 27 + (20 - ft.Height) / 2));
            }
            double x0 = (w - lanes.Count * Cell) / 2;
            for (int i = 0; i < lanes.Count; i++)
            {
                var lane = lanes[i];
                double cx = x0 + Cell * (i + 0.5);
                if (i > 0) dc.DrawLine(Divider, new Point(x0 + Cell * i, 10), new Point(x0 + Cell * i, h - 30));
                if (lane.Alt is double alt) Arrow(dc, cx, h, alt, Faint, false);
                if (lane.Angle is not double a) continue;
                if (lane.Preferred) Arrow(dc, cx, h, a, Green, true);
                else Arrow(dc, cx, h, a, White, false);
            }
        }

        /// <summary>Arrow rising from the bottom of the lane cell: a stem, then a bend of deg (+ = right) ending in a head.</summary>
        static void Arrow(DrawingContext dc, double cx, double h, double deg, Brush color, bool bright)
        {
            const double w = 7, top = 14, hl = 13, hw = 11;
            double bottom = h - 34;
            double a = Math.Max(-100, Math.Min(100, deg)), th = a * Math.PI / 180;
            double dx = Math.Sin(th), dy = -Math.Cos(th);
            bool straight = Math.Abs(a) < 12;
            double bendY = straight ? top + 8 : (top + bottom) / 2, len = straight ? 0 : 24;
            double ex = cx + dx * len, ey = bendY + dy * len;
            double tipX = straight ? cx : ex, tipY = straight ? top : ey, fx = straight ? 0 : dx, fy = straight ? -1 : dy;

            var shaft = new StreamGeometry();
            using (var g = shaft.Open())
            {
                g.BeginFigure(new Point(cx, bottom), false, false);
                if (straight) g.LineTo(new Point(cx, tipY + hl * 0.97), true, true);
                else
                {
                    g.LineTo(new Point(cx, bendY), true, true);
                    g.LineTo(new Point(ex - fx * hl * 0.97, ey - fy * hl * 0.97), true, true);
                }
            }
            shaft.Freeze();
            if (bright) dc.DrawGeometry(null, StrokePen(GreenGlow, w + 7), shaft);
            dc.DrawGeometry(null, StrokePen(color, w), shaft);

            double bx = tipX - fx * hl, by = tipY - fy * hl;
            var head = new StreamGeometry();
            using (var g = head.Open())
            {
                g.BeginFigure(new Point(tipX, tipY), true, true);
                g.LineTo(new Point(bx - fy * hw, by + fx * hw), true, false);
                g.LineTo(new Point(bx + fy * hw, by - fx * hw), true, false);
            }
            head.Freeze();
            dc.DrawGeometry(color, null, head);
        }

        static Pen StrokePen(Brush b, double width)
        {
            var p = new Pen(b, width) { StartLineCap = PenLineCap.Flat, EndLineCap = PenLineCap.Flat, LineJoin = PenLineJoin.Round };
            p.Freeze();
            return p;
        }
    }
}
