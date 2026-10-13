using System;
using System.Collections.Generic;
using System.Net.Http;
using System.Text.Json;
using System.Threading;
using System.Threading.Tasks;

namespace LaneOverlay
{
    /// <summary>Latest lane panel state from the companion (GET /lanes).</summary>
    public sealed class LaneState
    {
        public IReadOnlyList<Lane>? Lanes { get; init; }
        public string Caption { get; init; } = "";
        public double? Age { get; init; }   // seconds since the app last sent it
    }

    /// <summary>Polls the companion a few times a second; reports null when it cannot be reached.</summary>
    public sealed class LaneClient : IDisposable
    {
        readonly HttpClient http = new HttpClient { Timeout = TimeSpan.FromSeconds(1.2) };
        readonly CancellationTokenSource stop = new CancellationTokenSource();
        public Func<string> Server { get; set; } = () => "http://127.0.0.1:31378";
        public event Action<LaneState?>? Updated;

        public void Start() => Task.Run(Loop);

        async Task Loop()
        {
            while (!stop.IsCancellationRequested)
            {
                LaneState? st = null;
                try
                {
                    string json = await http.GetStringAsync(Server().TrimEnd('/') + "/lanes", stop.Token);
                    st = Parse(json);
                }
                catch { /* unreachable: st stays null */ }
                Updated?.Invoke(st);
                try { await Task.Delay(250, stop.Token); } catch { return; }
            }
        }

        public static LaneState Parse(string json)
        {
            using var doc = JsonDocument.Parse(json);
            var root = doc.RootElement;
            List<Lane>? lanes = null;
            if (root.TryGetProperty("lanes", out var arr) && arr.ValueKind == JsonValueKind.Array)
            {
                lanes = new List<Lane>();
                foreach (var e in arr.EnumerateArray())
                    lanes.Add(new Lane(Num(e, "a"), e.TryGetProperty("p", out var p) && p.ValueKind == JsonValueKind.True, Num(e, "alt")));
            }
            return new LaneState
            {
                Lanes = lanes,
                Caption = root.TryGetProperty("caption", out var c) && c.ValueKind == JsonValueKind.String ? c.GetString() ?? "" : "",
                Age = Num(root, "age"),
            };
        }

        static double? Num(JsonElement e, string name) =>
            e.TryGetProperty(name, out var v) && v.ValueKind == JsonValueKind.Number ? v.GetDouble() : null;

        public void Dispose() { stop.Cancel(); http.Dispose(); }
    }
}
