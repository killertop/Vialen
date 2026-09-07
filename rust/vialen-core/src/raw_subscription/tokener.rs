//! Android JSONTokener-compatible syntax, including its legacy relaxed grammar.
//! Behavior reference: AOSP libcore json/JSONTokener.java (Apache-2.0).
//! Only one value is consumed; trailing input is deliberately ignored.
use super::Result;
use serde_json::{Map, Number, Value};
pub(super) fn parse(text: &str) -> Result<Value> {
    let text = text.strip_prefix('\u{feff}').unwrap_or(text);
    Parser {
        chars: text.chars().collect(),
        pos: 0,
    }
    .value(0)
}
struct Parser {
    chars: Vec<char>,
    pos: usize,
}
impl Parser {
    fn next(&mut self) -> Option<char> {
        let c = self.chars.get(self.pos).copied();
        if c.is_some() {
            self.pos += 1;
        }
        c
    }
    fn clean(&mut self) -> Result<Option<char>> {
        while let Some(c) = self.next() {
            match c {
                '\t' | ' ' | '\n' | '\r' => {}
                '#' => self.line(),
                '/' if self.chars.get(self.pos) == Some(&'/') => {
                    self.pos += 1;
                    self.line();
                }
                '/' if self.chars.get(self.pos) == Some(&'*') => {
                    self.pos += 1;
                    let mut found = false;
                    while let Some(c) = self.next() {
                        if c == '*' && self.chars.get(self.pos) == Some(&'/') {
                            self.pos += 1;
                            found = true;
                            break;
                        }
                    }
                    if !found {
                        return Err("INVALID_JSON_COMMENT");
                    }
                }
                _ => return Ok(Some(c)),
            }
        }
        Ok(None)
    }
    fn line(&mut self) {
        while let Some(c) = self.next() {
            if matches!(c, '\r' | '\n') {
                break;
            }
        }
    }
    fn value(&mut self, depth: usize) -> Result<Value> {
        if depth > 128 {
            return Err("EXCESSIVE_JSON_DEPTH");
        }
        match self.clean()?.ok_or("MISSING_JSON_VALUE")? {
            '{' => self.object(depth + 1),
            '[' => self.array(depth + 1),
            quote @ ('\'' | '"') => self.string(quote).map(Value::String),
            _ => {
                self.pos -= 1;
                self.literal()
            }
        }
    }
    fn string(&mut self, quote: char) -> Result<String> {
        let mut out = Vec::<u16>::new();
        while let Some(c) = self.next() {
            if c == quote {
                return String::from_utf16(&out).map_err(|_| "INVALID_JSON_UTF16");
            }
            let c = if c == '\\' {
                match self.next().ok_or("INVALID_JSON_ESCAPE")? {
                    'u' => {
                        let mut hex = String::new();
                        for _ in 0..4 {
                            hex.push(self.next().ok_or("INVALID_JSON_ESCAPE")?);
                        }
                        let n = i32::from_str_radix(&hex, 16).map_err(|_| "INVALID_JSON_ESCAPE")?;
                        out.push(n as u16);
                        continue;
                    }
                    't' => '\t',
                    'b' => '\u{8}',
                    'n' => '\n',
                    'r' => '\r',
                    'f' => '\u{c}',
                    c => c,
                }
            } else {
                c
            };
            let mut encoded = [0; 2];
            out.extend_from_slice(c.encode_utf16(&mut encoded));
        }
        Err("UNTERMINATED_JSON_STRING")
    }
    fn literal(&mut self) -> Result<Value> {
        let start = self.pos;
        while let Some(&c) = self.chars.get(self.pos) {
            if "{}[]/\\:,=;# \t\u{c}\r\n".contains(c) {
                break;
            }
            self.pos += 1;
        }
        let literal: String = self.chars[start..self.pos].iter().collect();
        if literal.is_empty() {
            return Err("MISSING_JSON_LITERAL");
        }
        match literal.to_ascii_lowercase().as_str() {
            "null" => return Ok(Value::Null),
            "true" => return Ok(Value::Bool(true)),
            "false" => return Ok(Value::Bool(false)),
            _ => {}
        }
        if !literal.contains('.') {
            let (radix, number) = if literal.starts_with("0x") || literal.starts_with("0X") {
                (16, &literal[2..])
            } else if literal.starts_with('0') && literal.len() > 1 {
                (8, &literal[1..])
            } else {
                (10, literal.as_str())
            };
            if radix == 10
                && let Some(n) = crate::config::protocols::int(number)
            {
                return Ok(Value::Number(n.into()));
            }
            if let Ok(n) = i64::from_str_radix(number, radix) {
                return Ok(Value::Number(n.into()));
            }
        }
        if let Ok(n) = literal.parse::<f64>() {
            return Number::from_f64(n)
                .map(Value::Number)
                .ok_or("NONFINITE_JSON_NUMBER");
        }
        Ok(Value::String(literal))
    }
    fn object(&mut self, depth: usize) -> Result<Value> {
        let mut out = Map::new();
        if self.clean()? == Some('}') {
            return Ok(Value::Object(out));
        }
        self.pos = self.pos.saturating_sub(1);
        loop {
            let key = self
                .value(depth)?
                .as_str()
                .ok_or("NONSTRING_JSON_KEY")?
                .to_owned();
            match self.clean()? {
                Some(':') => {}
                Some('=') => {
                    if self.chars.get(self.pos) == Some(&'>') {
                        self.pos += 1;
                    }
                }
                _ => return Err("MISSING_JSON_COLON"),
            }
            out.insert(key, self.value(depth)?);
            match self.clean()? {
                Some('}') => return Ok(Value::Object(out)),
                Some(',' | ';') => {}
                _ => return Err("UNTERMINATED_JSON_OBJECT"),
            }
        }
    }
    fn array(&mut self, depth: usize) -> Result<Value> {
        let mut out = vec![];
        let mut separated = false;
        loop {
            match self.clean()? {
                None => return Err("UNTERMINATED_JSON_ARRAY"),
                Some(']') => {
                    if separated {
                        out.push(Value::Null);
                    }
                    return Ok(Value::Array(out));
                }
                Some(',' | ';') => {
                    out.push(Value::Null);
                    separated = true;
                    continue;
                }
                Some(_) => self.pos -= 1,
            }
            out.push(self.value(depth)?);
            match self.clean()? {
                Some(']') => return Ok(Value::Array(out)),
                Some(',' | ';') => separated = true,
                _ => return Err("UNTERMINATED_JSON_ARRAY"),
            }
        }
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;
    #[test]
    fn relaxed_grammar_and_trailing_input() {
        assert_eq!(
            parse("\u{feff}/* c */ {server:'x';port=>010,other:0x10,ok:TRUE} ignored").unwrap(),
            json!({"server":"x","port":8,"other":16,"ok":true})
        );
        assert_eq!(parse("[;1,,2,]").unwrap(), json!([null, 1, null, 2, null]));
        assert!(parse("{x:1,}").is_err());
        assert!(parse("{1:x}").is_err());
        assert_eq!(parse(r#""\ud83d\ude00\q""#).unwrap(), json!("😀q"));
        assert!(parse(r#""\ud800""#).is_err());
    }
}
