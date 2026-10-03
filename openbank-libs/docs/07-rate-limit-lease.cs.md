# Životní cyklus propustky pro souběžné požadavky

Sdílený HTTP rate-limit filtr přiděluje každému přijatému požadavku jednu propustku. Filtr odpovědi, dokončení požadavku i uzavření spojení mohou zkusit její vrácení; atomická ochrana zajistí právě jedno vrácení, takže semafor i čítač aktivních požadavků se změní pouze jednou. Propustka se vrátí i tehdy, když streamovaná nebo přerušená odpověď neprojde běžným filtrem odpovědi. `X-RateLimit-Remaining` udává dostupné propustky v okamžiku filtrování odpovědi, nikoli budoucí rezervaci.
