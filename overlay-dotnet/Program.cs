using System;
using System.Collections.Generic;
using System.IO;
using System.Windows;
using System.Windows.Media;
using System.Windows.Media.Imaging;

namespace LaneOverlay
{
    public static class Program
    {
        // Ets2LaneOverlay.exe [--server http://host:31378] [--demo] [--snapshot out.png]
        [STAThread]
        public static int Main(string[] args)
        {
            string? server = null, snapshot = null;
            bool demo = false;
            for (int i = 0; i < args.Length; i++)
            {
                if (args[i] == "--server" && i + 1 < args.Length) server = args[++i];
                else if (args[i] == "--snapshot" && i + 1 < args.Length) snapshot = args[++i];
                else if (args[i] == "--demo") demo = true;
            }
            if (snapshot != null) return Snapshot(snapshot);

            var cfg = Settings.Load();
            if (server != null) cfg.Server = server.TrimEnd('/');
            var app = new Application { ShutdownMode = ShutdownMode.OnMainWindowClose };
            var win = new OverlayWindow(cfg, demo);
            return app.Run(win);
        }

        /// <summary>Self-test: renders demo lanes to a PNG without opening a window.</summary>
        static int Snapshot(string path)
        {
            var panel = new LanePanel
            {
                Lanes = new List<Lane> { new Lane(0, true, null), new Lane(-45, true, 0), new Lane(0, false, null), new Lane(90, false, null) },
                Caption = "12 km",
            };
            var size = LanePanel.DesignSize(4, "12 km");
            double s = 3;
            panel.Width = size.Width * s; panel.Height = size.Height * s;
            panel.Measure(new Size(panel.Width, panel.Height));
            panel.Arrange(new Rect(0, 0, panel.Width, panel.Height));
            var rtb = new RenderTargetBitmap((int)panel.Width, (int)panel.Height, 96, 96, PixelFormats.Pbgra32);
            rtb.Render(panel);
            var enc = new PngBitmapEncoder();
            enc.Frames.Add(BitmapFrame.Create(rtb));
            using var f = File.Create(path);
            enc.Save(f);
            return 0;
        }
    }
}
