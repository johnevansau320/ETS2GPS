using System;
using System.Collections.Generic;
using System.Runtime.InteropServices;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Interop;
using System.Windows.Media;
using System.Windows.Threading;

namespace LaneOverlay
{
    /// <summary>Borderless, always-on-top, movable and resizable window that shows the lane panel.</summary>
    public sealed class OverlayWindow : Window
    {
        const double Edge = 9, MinW = 110, MinH = 44, StaleSeconds = 6;
        [Flags] enum Edges { None = 0, Left = 1, Right = 2, Top = 4, Bottom = 8 }

        static readonly LaneState DemoState = new LaneState
        {
            Lanes = new List<Lane> { new Lane(0, true, null), new Lane(0, true, null), new Lane(12, false, null) },
            Caption = "6.4 km", Age = 0,
        };

        readonly Settings cfg;
        readonly LanePanel panel = new LanePanel();
        readonly LaneClient client = new LaneClient();
        readonly DispatcherTimer saveTimer = new DispatcherTimer { Interval = TimeSpan.FromMilliseconds(700) };
        readonly DispatcherTimer tick = new DispatcherTimer { Interval = TimeSpan.FromMilliseconds(500) };
        System.Windows.Forms.NotifyIcon? tray;
        LaneState? data;
        bool reachable, demo, clickThrough;

        // resize drag state
        Edges dragEdges = Edges.None;
        Point dragStart;
        Rect dragRect;

        public OverlayWindow(Settings settings, bool startDemo)
        {
            cfg = settings;
            demo = startDemo;
            Title = "ETS2 GPS lane panel";
            WindowStyle = WindowStyle.None;
            AllowsTransparency = true;
            ResizeMode = ResizeMode.NoResize;          // resizing is done by the edge handling below
            Background = new SolidColorBrush(Color.FromArgb(1, 0, 0, 0)); // nearly invisible, but still catches the mouse
            ShowInTaskbar = false;
            Topmost = cfg.Topmost;
            Opacity = Math.Max(0.15, Math.Min(1.0, cfg.Opacity));
            MinWidth = MinW; MinHeight = MinH;
            Width = Math.Max(MinW, cfg.Width); Height = Math.Max(MinH, cfg.Height);
            if (!double.IsNaN(cfg.Left) && !double.IsNaN(cfg.Top))
            {
                WindowStartupLocation = WindowStartupLocation.Manual;
                Left = cfg.Left; Top = cfg.Top;
            }
            else
            {
                WindowStartupLocation = WindowStartupLocation.Manual;
                Left = SystemParameters.WorkArea.Left + 100; Top = SystemParameters.WorkArea.Top + 100;
            }
            Content = panel;
            ContextMenu = BuildMenu();

            client.Server = () => cfg.Server;
            client.Updated += st => Dispatcher.BeginInvoke(new Action(() => { reachable = st != null; data = st; Refresh(); }));
            saveTimer.Tick += (_, _) => { saveTimer.Stop(); Persist(); };
            tick.Tick += (_, _) => { ApplyClickThrough(); Refresh(); };
            LocationChanged += (_, _) => { saveTimer.Stop(); saveTimer.Start(); };
            SizeChanged += (_, _) => { saveTimer.Stop(); saveTimer.Start(); panel.InvalidateVisual(); };
            Loaded += (_, _) => { client.Start(); tick.Start(); MakeTray(); Refresh(); };
            Closed += (_, _) => { Persist(); client.Dispose(); tray?.Dispose(); };
        }

        // --------------------------------------------------------------------------- state

        // a short gap (a missed poll, a momentary "no lanes") keeps the last panel on screen instead of blinking it off and on
        (IReadOnlyList<Lane> Lanes, string Caption)? lastGood; long lastGoodAt;
        const long HoldMs = 1500;

        (IReadOnlyList<Lane> Lanes, string Caption)? Current()
        {
            var d = demo ? DemoState : data;
            if (d?.Lanes != null && d.Lanes.Count > 0 && d.Age is double age && age <= StaleSeconds)
            {
                lastGood = (d.Lanes, d.Caption); lastGoodAt = Environment.TickCount64;
                return lastGood;
            }
            if (lastGood != null && Environment.TickCount64 - lastGoodAt < HoldMs) return lastGood;
            return null;
        }

        void Refresh()
        {
            var cur = Current();
            panel.Lanes = cur?.Lanes;
            panel.Caption = cur?.Caption ?? "";
            panel.Message = reachable ? "Waiting for lane data" : "Companion not reachable";
            panel.Placeholder = !cfg.HideWhenIdle;
            panel.InvalidateVisual();
        }

        void Persist()
        {
            if (WindowState == WindowState.Normal) { cfg.Left = Left; cfg.Top = Top; cfg.Width = Width; cfg.Height = Height; }
            cfg.Opacity = Math.Round(Opacity, 2);
            cfg.Topmost = Topmost;
            cfg.Save();
        }

        // ------------------------------------------------------- click-through when hidden-idle

        const int GWL_EXSTYLE = -20, WS_EX_TRANSPARENT = 0x20, WS_EX_LAYERED = 0x80000;
        [DllImport("user32.dll")] static extern int GetWindowLong(IntPtr hWnd, int nIndex);
        [DllImport("user32.dll")] static extern int SetWindowLong(IntPtr hWnd, int nIndex, int value);

        void ApplyClickThrough()
        {
            bool want = cfg.HideWhenIdle && Current() == null;
            if (want == clickThrough) return;
            clickThrough = want;
            var h = new WindowInteropHelper(this).Handle;
            if (h == IntPtr.Zero) return;
            int ex = GetWindowLong(h, GWL_EXSTYLE);
            ex = want ? (ex | WS_EX_TRANSPARENT | WS_EX_LAYERED) : (ex & ~WS_EX_TRANSPARENT);
            SetWindowLong(h, GWL_EXSTYLE, ex);
        }

        // ------------------------------------------------------------- mouse: move, resize, wheel

        Edges EdgesAt(Point p)
        {
            var e = Edges.None;
            if (p.X < Edge) e |= Edges.Left;
            if (p.X > ActualWidth - Edge) e |= Edges.Right;
            if (p.Y < Edge) e |= Edges.Top;
            if (p.Y > ActualHeight - Edge) e |= Edges.Bottom;
            return e;
        }

        Point ScreenDip(MouseEventArgs e)
        {
            var p = PointToScreen(e.GetPosition(this));
            var src = PresentationSource.FromVisual(this);
            return src?.CompositionTarget != null ? src.CompositionTarget.TransformFromDevice.Transform(p) : p;
        }

        protected override void OnMouseMove(MouseEventArgs e)
        {
            base.OnMouseMove(e);
            if (dragEdges != Edges.None && e.LeftButton == MouseButtonState.Pressed)
            {
                var p = ScreenDip(e);
                double dx = p.X - dragStart.X, dy = p.Y - dragStart.Y;
                double l = dragRect.Left, t = dragRect.Top, r = dragRect.Right, b = dragRect.Bottom;
                if (dragEdges.HasFlag(Edges.Left)) l = Math.Min(l + dx, r - MinW);
                if (dragEdges.HasFlag(Edges.Right)) r = Math.Max(r + dx, l + MinW);
                if (dragEdges.HasFlag(Edges.Top)) t = Math.Min(t + dy, b - MinH);
                if (dragEdges.HasFlag(Edges.Bottom)) b = Math.Max(b + dy, t + MinH);
                Left = l; Top = t; Width = r - l; Height = b - t;
                return;
            }
            var ed = EdgesAt(e.GetPosition(this));
            Cursor = ed switch
            {
                Edges.Left or Edges.Right => Cursors.SizeWE,
                Edges.Top or Edges.Bottom => Cursors.SizeNS,
                Edges.Left | Edges.Top or Edges.Right | Edges.Bottom => Cursors.SizeNWSE,
                Edges.Right | Edges.Top or Edges.Left | Edges.Bottom => Cursors.SizeNESW,
                _ => Cursors.SizeAll,
            };
        }

        protected override void OnMouseLeftButtonDown(MouseButtonEventArgs e)
        {
            base.OnMouseLeftButtonDown(e);
            var ed = EdgesAt(e.GetPosition(this));
            if (ed != Edges.None)
            {
                dragEdges = ed; dragStart = ScreenDip(e); dragRect = new Rect(Left, Top, Width, Height);
                CaptureMouse();
            }
            else
            {
                try { DragMove(); } catch (InvalidOperationException) { /* button released already */ }
            }
        }

        protected override void OnMouseLeftButtonUp(MouseButtonEventArgs e)
        {
            base.OnMouseLeftButtonUp(e);
            if (dragEdges != Edges.None) { dragEdges = Edges.None; ReleaseMouseCapture(); }
        }

        protected override void OnMouseWheel(MouseWheelEventArgs e)
        {
            base.OnMouseWheel(e);
            int step = e.Delta > 0 ? 1 : -1;
            if (Keyboard.Modifiers.HasFlag(ModifierKeys.Control)) SetOpacity(Opacity + 0.05 * step);
            else
            {
                double f = step > 0 ? 1.08 : 1 / 1.08;
                Width = Math.Max(MinW, Width * f); Height = Math.Max(MinH, Height * f);
            }
        }

        void SetOpacity(double v)
        {
            Opacity = Math.Max(0.15, Math.Min(1.0, v));
            saveTimer.Stop(); saveTimer.Start();
        }

        // ------------------------------------------------------------------- menu and tray icon

        ContextMenu BuildMenu()
        {
            var m = new ContextMenu();

            var slider = new Slider { Minimum = 15, Maximum = 100, Value = Math.Round(Opacity * 100), Width = 190, IsMoveToPointEnabled = true };
            var label = new TextBlock { Text = "Opacity " + Math.Round(Opacity * 100) + "%", Margin = new Thickness(0, 0, 0, 2) };
            slider.ValueChanged += (_, e) => { label.Text = "Opacity " + (int)e.NewValue + "%"; SetOpacity(e.NewValue / 100.0); };
            var box = new StackPanel { Margin = new Thickness(4, 2, 4, 2) };
            box.Children.Add(label); box.Children.Add(slider);
            m.Items.Add(new MenuItem { Header = box, StaysOpenOnClick = true });
            m.Opened += (_, _) => { slider.Value = Math.Round(Opacity * 100); label.Text = "Opacity " + Math.Round(Opacity * 100) + "%"; };

            var size = new MenuItem { Header = "Size" };
            foreach (var (name, w) in new[] { ("Small", 220.0), ("Medium", 330.0), ("Large", 480.0), ("Extra large", 700.0) })
            {
                var it = new MenuItem { Header = name };
                it.Click += (_, _) => SetWidth(w);
                size.Items.Add(it);
            }
            m.Items.Add(size);
            m.Items.Add(new Separator());

            var top = new MenuItem { Header = "Always on top", IsCheckable = true, IsChecked = Topmost };
            top.Click += (_, _) => { Topmost = top.IsChecked; Persist(); };
            m.Items.Add(top);
            var hide = new MenuItem { Header = "Hide when there is no lane data", IsCheckable = true, IsChecked = cfg.HideWhenIdle };
            hide.Click += (_, _) => { cfg.HideWhenIdle = hide.IsChecked; Persist(); ApplyClickThrough(); Refresh(); };
            m.Items.Add(hide);
            var dm = new MenuItem { Header = "Show demo lanes (for placing)", IsCheckable = true, IsChecked = demo };
            dm.Click += (_, _) => { demo = dm.IsChecked; Refresh(); };
            m.Items.Add(dm);
            m.Items.Add(new Separator());

            var srv = new MenuItem { Header = "Server address…" };
            srv.Click += (_, _) => AskServer();
            m.Items.Add(srv);
            var reset = new MenuItem { Header = "Reset position and size" };
            reset.Click += (_, _) => { Width = 330; Height = 150; Left = SystemParameters.WorkArea.Left + 100; Top = SystemParameters.WorkArea.Top + 100; Persist(); };
            m.Items.Add(reset);
            m.Items.Add(new Separator());
            var quit = new MenuItem { Header = "Quit" };
            quit.Click += (_, _) => Close();
            m.Items.Add(quit);
            return m;
        }

        void SetWidth(double w)
        {
            var cur = Current();
            var ds = LanePanel.DesignSize(cur?.Lanes.Count ?? 3, cur?.Caption ?? "");
            Width = w; Height = Math.Max(MinH, w * ds.Height / ds.Width);
        }

        void AskServer()
        {
            var tb = new TextBox { Text = cfg.Server, Margin = new Thickness(0, 6, 0, 10), MinWidth = 320 };
            var ok = new Button { Content = "OK", Width = 80, IsDefault = true, Margin = new Thickness(0, 0, 8, 0) };
            var cancel = new Button { Content = "Cancel", Width = 80, IsCancel = true };
            var row = new StackPanel { Orientation = Orientation.Horizontal, HorizontalAlignment = HorizontalAlignment.Right };
            row.Children.Add(ok); row.Children.Add(cancel);
            var sp = new StackPanel { Margin = new Thickness(14) };
            sp.Children.Add(new TextBlock { Text = "Companion address (for example http://192.168.1.20:31378):" });
            sp.Children.Add(tb); sp.Children.Add(row);
            var dlg = new Window { Title = "Companion address", Content = sp, SizeToContent = SizeToContent.WidthAndHeight, ResizeMode = ResizeMode.NoResize, Topmost = true, WindowStartupLocation = WindowStartupLocation.CenterScreen, ShowInTaskbar = false };
            ok.Click += (_, _) => dlg.DialogResult = true;
            if (dlg.ShowDialog() == true && tb.Text.Trim().Length > 0)
            {
                string t = tb.Text.Trim();
                if (!t.StartsWith("http", StringComparison.OrdinalIgnoreCase)) t = "http://" + t;
                cfg.Server = t.TrimEnd('/'); Persist();
            }
        }

        void MakeTray()
        {
            try
            {
                using var bmp = new System.Drawing.Bitmap(32, 32);
                using (var g = System.Drawing.Graphics.FromImage(bmp))
                {
                    g.SmoothingMode = System.Drawing.Drawing2D.SmoothingMode.AntiAlias;
                    g.Clear(System.Drawing.Color.Transparent);
                    using var back = new System.Drawing.SolidBrush(System.Drawing.Color.FromArgb(38, 46, 62));
                    g.FillRectangle(back, 1, 1, 30, 30);
                    using var green = new System.Drawing.SolidBrush(System.Drawing.Color.FromArgb(60, 235, 130));
                    g.FillRectangle(green, 13, 14, 6, 14);
                    g.FillPolygon(green, new[] { new System.Drawing.Point(16, 4), new System.Drawing.Point(25, 15), new System.Drawing.Point(7, 15) });
                }
                tray = new System.Windows.Forms.NotifyIcon
                {
                    Icon = System.Drawing.Icon.FromHandle(bmp.GetHicon()),
                    Text = "ETS2 GPS lane panel",
                    Visible = true,
                };
                tray.MouseUp += (_, _) => Dispatcher.BeginInvoke(new Action(() =>
                {
                    ContextMenu.Placement = System.Windows.Controls.Primitives.PlacementMode.MousePoint;
                    ContextMenu.PlacementTarget = this;
                    Activate();
                    ContextMenu.IsOpen = true;
                }));
            }
            catch { /* no tray available: the window's own right-click menu still works */ }
        }
    }
}
