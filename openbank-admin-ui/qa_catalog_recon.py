from playwright.sync_api import sync_playwright
import time

with sync_playwright() as p:
    browser = p.chromium.launch(headless=True)
    context = browser.new_context()
    page = context.new_page()
    
    print("Navigating to admin UI...")
    page.goto('http://localhost:3000/product-catalog')
    page.wait_for_load_state('networkidle')
    
    if "Přihlásit se" in page.content() or "Log in" in page.content() or "Sign in" in page.content():
        print("Clicking login button...")
        login_btn = page.locator('button', has_text="Keycloak SSO")
        if login_btn.count() > 0:
            login_btn.click()
            page.wait_for_load_state('networkidle')
            
            page.fill('input[name="username"]', 'admin@openbank.local')
            page.fill('input[name="password"]', 'Admin1234!')
            page.click('input[name="login"]')
            page.wait_for_load_state('networkidle')
            
    print("Final Page title:", page.title())
    print("URL:", page.url)
    
    print("Capturing DOM for product catalog...")
    buttons = page.locator('button').all_inner_texts()
    print("Buttons found:", buttons)
    
    html = page.content()
    with open('dom_dump_catalog.html', 'w') as f:
        f.write(html)
        
    page.screenshot(path='catalog.png', full_page=True)
    browser.close()
