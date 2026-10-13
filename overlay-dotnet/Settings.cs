using System;
using System.IO;
using System.Text.Json;

namespace LaneOverlay
{
    /// <summary>Remembered window position, size and options (%APPDATA%\ETS2-GPS\lane_overlay_net.json).</summary>
    public sealed class Settings
    {
        public double Left { get; set; } = double.NaN;
        public double Top { get; set; } = double.NaN;
        public double Width { get; set; } = 330;
        public double Height { get; set; } = 150;
        public double Opacity { get; set; } = 0.9;
        public bool Topmost { get; set; } = true;
        public bool HideWhenIdle { get; set; } = false;
        public string Server { get; set; } = "http://127.0.0.1:31378";

        public static string FilePath =>
            Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "ETS2-GPS", "lane_overlay_net.json");

        public static Settings Load()
        {
            try { return JsonSerializer.Deserialize<Settings>(File.ReadAllText(FilePath)) ?? new Settings(); }
            catch { return new Settings(); }
        }

        public void Save()
        {
            try
            {
                Directory.CreateDirectory(Path.GetDirectoryName(FilePath)!);
                File.WriteAllText(FilePath, JsonSerializer.Serialize(this, new JsonSerializerOptions { WriteIndented = true }));
            }
            catch { /* read-only profile: not fatal */ }
        }
    }
}
